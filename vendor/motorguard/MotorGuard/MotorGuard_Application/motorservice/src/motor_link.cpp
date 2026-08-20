#include "motorguard/someip/motor_link.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include <chrono>
#include <mutex>
#include <thread>

#include <ifaddrs.h>
#include <net/if.h>

#include <android/log.h>
#include <android/multinetwork.h>

#define TAG "MotorGuardLink"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace motorguard::someip {
namespace {

using Clock = std::chrono::steady_clock;
using Ms = std::chrono::milliseconds;

// A datagram bigger than this is not one of ours. The event is 32 bytes with
// its header and the largest SD message we will ever see is a few hundred.
constexpr size_t kDatagramBuf = 2048;

// Refuse to allocate for a capture response that claims to be larger than any
// plausible one. 12 channels x 20 kHz x 60 s x 4 B is 57 MB; the specified
// window is 10 s. A peer claiming more than this is confused or hostile, and
// either way the answer is not to try.
constexpr uint32_t kMaxCapturePayload = 64u * 1024 * 1024;

constexpr int kConnectTimeoutMs = 3000;

int64_t nowMs() {
    return std::chrono::duration_cast<Ms>(Clock::now().time_since_epoch()).count();
}

bool setNonBlocking(int fd) {
    const int flags = fcntl(fd, F_GETFL, 0);
    return flags >= 0 && fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

// Falls back to when android_setsocknetwork has nothing to bind to (handle ==
// NETWORK_UNSPECIFIED) or is unavailable for some other reason on this image.
// Board-specific -- the Pi's onboard Ethernet is always eth0, which is what
// this whole link exists to reach -- rather than a general answer for any
// device this code might run on, but it is the one confirmed on the bench to
// actually move traffic off Wi-Fi: android_setsocknetwork() alone was
// verified to compile and link, never end-to-end on hardware, because
// resolving a Network handle happens in Kotlin (SomeIpVehicleData, over
// ConnectivityManager) and nothing here can prove that resolution behaves
// the same on a real system image as it did on paper. SO_BINDTODEVICE is
// what was actually driven through discovery, subscribe and a live event
// arriving with the guest's own vtnet2 fix on the other end -- see the
// commit this landed in. Applied unconditionally, not only when handle is
// unset: the two mechanisms bind different kernel state (a socket mark
// versus the interface itself) and do not conflict, so this is a floor
// under android_setsocknetwork rather than a competing path with it.
constexpr const char* kFallbackIface = "eth0";

void bindToNetwork(int fd, uint64_t handle, const char* what) {
    if (handle != NETWORK_UNSPECIFIED) {
        if (const int err = android_setsocknetwork(static_cast<net_handle_t>(handle), fd);
            err != 0) {
            LOGW("%s: android_setsocknetwork failed (%s)", what, strerror(-err));
        }
    }
    if (setsockopt(fd, SOL_SOCKET, SO_BINDTODEVICE, kFallbackIface, strlen(kFallbackIface) + 1) !=
        0) {
        LOGW("%s: SO_BINDTODEVICE %s failed (%s)", what, kFallbackIface, strerror(errno));
    }
}

// The diagnostics unit's whole LAN -- meta-qnx-hyp's bridge is 192.168.2.0/24
// on every board this ships to, the same way the guest's own address
// (192.168.2.3) and the host's (192.168.2.2) are static throughout that
// project rather than DHCP-discovered. Naming the network here, not a host on
// it, is what tells localAddressOnLan() which of eth0's addresses is the one
// that matters when the interface carries more than one -- see there.
constexpr uint32_t kLanNetworkBe = 0x0002A8C0;   // 192.168.2.0, network order
constexpr uint32_t kLanNetmaskBe = 0x00FFFFFF;   // 255.255.255.0, network order

// This device's own address on the diagnostics LAN, found by walking every
// local IPv4 address (getifaddrs) for the one whose network matches
// kLanNetworkBe/kLanNetmaskBe -- not by asking the kernel to pick one for us.
//
// The difference matters on this exact board: eth0 here carries BOTH
// 192.168.2.60/24 (the LAN) and 10.42.0.2/24 (unrelated -- some other
// service's doing, not this app's), and neither SO_BINDTODEVICE nor
// android_setsocknetwork disambiguates between two addresses on the SAME
// interface. Confirmed on the bench: pinning the interface alone still left
// 10.42.0.2 as the source on every outgoing packet, an address the
// diagnostics unit has no route back to, which is a silent failure --
// discovery completes, subscribe is acknowledged, and events simply never
// arrive because the reply address embedded in the subscribe was never
// reachable to begin with.
//
// Returns 0 if nothing matches, which callers treat as "fall back" rather
// than as this device having no address at all.
uint32_t localAddressOnLan() {
    ifaddrs* addrs = nullptr;
    if (getifaddrs(&addrs) != 0) return 0;
    uint32_t result = 0;
    for (const ifaddrs* a = addrs; a != nullptr; a = a->ifa_next) {
        if (a->ifa_addr == nullptr || a->ifa_addr->sa_family != AF_INET) continue;
        const auto addr = reinterpret_cast<sockaddr_in*>(a->ifa_addr)->sin_addr.s_addr;
        if ((addr & kLanNetmaskBe) == (kLanNetworkBe & kLanNetmaskBe)) {
            result = addr;
            break;
        }
    }
    freeifaddrs(addrs);
    return result;
}

// The local unicast address the peer would see us at.
//
// localAddressOnLan() first: on THIS board, it is the only answer that is
// actually right, because the routing-table trick below cannot tell
// 192.168.2.60 and 10.42.0.2 apart -- both are valid local addresses on the
// same interface, and which one the kernel picks for a connect()ed socket's
// source is not something this code controls.
//
// The connect()+getsockname() fallback stays for a board that is NOT on
// 192.168.2.0/24 -- a different bench, a different LAN -- where it is
// still the right general answer: connect a throwaway UDP socket and read
// back the local end, which picks the correct interface on a device with
// more than one route without this file needing to know its address plan.
uint32_t localAddressFor(uint32_t peerBe, uint64_t networkHandle) {
    if (const uint32_t lan = localAddressOnLan(); lan != 0) return lan;

    const int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return 0;
    bindToNetwork(fd, networkHandle, "local-address probe");

    sockaddr_in peer{};
    peer.sin_family = AF_INET;
    peer.sin_addr.s_addr = peerBe;
    peer.sin_port = htons(9);  // discard; nothing is sent

    uint32_t local = 0;
    if (connect(fd, reinterpret_cast<sockaddr*>(&peer), sizeof peer) == 0) {
        sockaddr_in self{};
        socklen_t len = sizeof self;
        if (getsockname(fd, reinterpret_cast<sockaddr*>(&self), &len) == 0) {
            local = self.sin_addr.s_addr;
        }
    }
    close(fd);
    return local;
}

std::string ipToString(uint32_t addrBe) {
    char buf[INET_ADDRSTRLEN] = {};
    in_addr a{};
    a.s_addr = addrBe;
    inet_ntop(AF_INET, &a, buf, sizeof buf);
    return buf;
}

}  // namespace

struct MotorLink::Impl {
    MotorLinkConfig cfg;
    EventFn onEvent;
    StateFn onState;

    int sdFd = -1;
    int evFd = -1;
    int wakeFd[2] = {-1, -1};
    uint16_t eventPort = 0;

    std::thread thread;
    std::atomic<bool> running{false};

    // Peer state. Read by requestCapture() on a caller thread, written by the
    // link thread, hence the mutex — it is held only across a struct copy.
    std::mutex mu;
    sd::Endpoint udpEp;
    sd::Endpoint tcpEp;
    LinkState state = LinkState::kDown;

    uint16_t session = 1;
    std::atomic<bool> refind{false};

    // Capture, owned by whichever thread is inside requestCapture().
    std::atomic<int> captureFd{-1};
    std::atomic<bool> captureCancel{false};
    std::atomic<bool> captureBusy{false};
    std::atomic<uint16_t> captureSession{1};

    ~Impl() {
        if (sdFd >= 0) close(sdFd);
        if (evFd >= 0) close(evFd);
        if (wakeFd[0] >= 0) close(wakeFd[0]);
        if (wakeFd[1] >= 0) close(wakeFd[1]);
    }

    void wake() {
        if (wakeFd[1] >= 0) {
            const uint8_t b = 1;
            ssize_t ignored = write(wakeFd[1], &b, 1);
            (void)ignored;
        }
    }

    void setState(LinkState s) {
        bool changed = false;
        {
            std::lock_guard<std::mutex> lock(mu);
            changed = (state != s);
            state = s;
        }
        if (changed && onState) onState(s);
    }

    uint16_t nextSession() {
        // Session ids wrap to 1, never to 0: 0 means "not tracked" and some
        // stacks drop the message rather than say so.
        if (++session == 0) session = 1;
        return session;
    }

    bool openSockets();
    void run();
    void sendFind();
    void sendSubscribe(uint32_t ttl);
    void handleSd(const uint8_t* p, size_t len);
    void handleEvent(const uint8_t* p, size_t len);
    void dropPeer(const char* why);
};

bool MotorLink::Impl::openSockets() {
    if (pipe(wakeFd) != 0) {
        LOGE("wake pipe: %s", strerror(errno));
        return false;
    }
    setNonBlocking(wakeFd[0]);

    // Event socket first: its port goes into the subscribe, so it has to be
    // known before any discovery traffic is sent.
    evFd = socket(AF_INET, SOCK_DGRAM, 0);
    if (evFd < 0) {
        LOGE("event socket: %s", strerror(errno));
        return false;
    }
    bindToNetwork(evFd, cfg.androidNetworkHandle, "event socket");
    int one = 1;
    setsockopt(evFd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);

    sockaddr_in evAddr{};
    evAddr.sin_family = AF_INET;
    evAddr.sin_addr.s_addr = htonl(INADDR_ANY);
    evAddr.sin_port = htons(cfg.localEventPort);
    if (bind(evFd, reinterpret_cast<sockaddr*>(&evAddr), sizeof evAddr) != 0) {
        LOGE("event bind :%u: %s", cfg.localEventPort, strerror(errno));
        return false;
    }
    socklen_t len = sizeof evAddr;
    if (getsockname(evFd, reinterpret_cast<sockaddr*>(&evAddr), &len) == 0) {
        eventPort = ntohs(evAddr.sin_port);
    }
    setNonBlocking(evFd);

    if (cfg.sdMulticast.empty()) {
        LOGI("service discovery disabled; static peer only");
        return true;
    }

    sdFd = socket(AF_INET, SOCK_DGRAM, 0);
    if (sdFd < 0) {
        LOGE("sd socket: %s", strerror(errno));
        return false;
    }
    // Before the port bind and, more importantly, before IP_ADD_MEMBERSHIP
    // below: imr_interface is INADDR_ANY there, so which interface the join
    // actually lands on follows this socket's routing -- exactly the policy
    // android_setsocknetwork() installs. Joined on the wrong interface first
    // and marked after is not the same thing; the group membership itself
    // would still be Wi-Fi's.
    bindToNetwork(sdFd, cfg.androidNetworkHandle, "sd socket");
    setsockopt(sdFd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);

    sockaddr_in sdAddr{};
    sdAddr.sin_family = AF_INET;
    sdAddr.sin_addr.s_addr = htonl(INADDR_ANY);
    sdAddr.sin_port = htons(cfg.sdPort);
    if (bind(sdFd, reinterpret_cast<sockaddr*>(&sdAddr), sizeof sdAddr) != 0) {
        LOGE("sd bind :%u: %s", cfg.sdPort, strerror(errno));
        return false;
    }

    // localAddressOnLan() over INADDR_ANY: with ANY, which interface the join
    // lands on follows whatever the kernel's default route is (Wi-Fi on this
    // board), same as the reasoning above bindToNetwork() -- see there. 0
    // (not found, e.g. a board off 192.168.2.0/24 entirely) falls back to
    // ANY rather than failing the join outright.
    const uint32_t joinAddr = localAddressOnLan();

    ip_mreq mreq{};
    if (inet_pton(AF_INET, cfg.sdMulticast.c_str(), &mreq.imr_multiaddr) != 1) {
        LOGE("bad multicast address %s", cfg.sdMulticast.c_str());
        return false;
    }
    mreq.imr_interface.s_addr = joinAddr != 0 ? joinAddr : htonl(INADDR_ANY);
    if (setsockopt(sdFd, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mreq, sizeof mreq) != 0) {
        // Not fatal: a static peer still works, and this is exactly what fails
        // on a bench network with no multicast route. Say which it was.
        LOGW("multicast join %s failed (%s) — discovery will not work",
             cfg.sdMulticast.c_str(), strerror(errno));
    }

    // The send-side twin of the join above. IP_ADD_MEMBERSHIP only pins where
    // this socket RECEIVES the group's traffic; sendto() to a multicast
    // destination still follows the kernel's own source-address selection
    // for whatever interface SO_BINDTODEVICE/android_setsocknetwork picked,
    // which on this board's eth0 -- two addresses, one interface -- is not
    // reliably the LAN one. Confirmed on the bench: without this, FIND left
    // on eth0 as documented, sourced from the OTHER address, and the
    // diagnostics unit never saw it arrive from anywhere it could answer.
    if (joinAddr != 0) {
        in_addr mcastIf{};
        mcastIf.s_addr = joinAddr;
        if (setsockopt(sdFd, IPPROTO_IP, IP_MULTICAST_IF, &mcastIf, sizeof mcastIf) != 0) {
            LOGW("multicast egress pin failed (%s)", strerror(errno));
        }
    }

    setNonBlocking(sdFd);
    return true;
}

void MotorLink::Impl::sendFind() {
    if (sdFd < 0) return;
    const auto msg = sd::buildFind(cfg.serviceId, cfg.instanceId, cfg.majorVersion, nextSession());

    sockaddr_in dst{};
    dst.sin_family = AF_INET;
    dst.sin_port = htons(cfg.sdPort);
    inet_pton(AF_INET, cfg.sdMulticast.c_str(), &dst.sin_addr);

    if (sendto(sdFd, msg.data(), msg.size(), 0, reinterpret_cast<sockaddr*>(&dst), sizeof dst) < 0) {
        LOGW("find send: %s", strerror(errno));
    }
}

void MotorLink::Impl::sendSubscribe(uint32_t ttl) {
    sd::Endpoint peer;
    {
        std::lock_guard<std::mutex> lock(mu);
        peer = udpEp;
    }
    if (!peer.valid()) return;

    const uint32_t local = localAddressFor(peer.addressBe, cfg.androidNetworkHandle);
    if (local == 0) {
        LOGW("no route to %s; cannot subscribe", ipToString(peer.addressBe).c_str());
        return;
    }

    const auto msg = sd::buildSubscribe(cfg.serviceId, cfg.instanceId, cfg.majorVersion,
                                        cfg.eventgroupId, ttl, local, eventPort, nextSession());

    // Subscriptions go unicast to the peer's SD port, not to the group: the
    // rest of the bus has no interest in who is listening to what.
    sockaddr_in dst{};
    dst.sin_family = AF_INET;
    dst.sin_port = htons(cfg.sdPort);
    dst.sin_addr.s_addr = peer.addressBe;

    const int fd = sdFd >= 0 ? sdFd : evFd;
    if (sendto(fd, msg.data(), msg.size(), 0, reinterpret_cast<sockaddr*>(&dst), sizeof dst) < 0) {
        LOGW("subscribe send: %s", strerror(errno));
    }
}

void MotorLink::Impl::dropPeer(const char* why) {
    {
        std::lock_guard<std::mutex> lock(mu);
        udpEp = {};
        tcpEp = {};
    }
    LOGI("peer dropped: %s", why);
    setState(LinkState::kDown);
}

void MotorLink::Impl::handleSd(const uint8_t* p, size_t len) {
    sd::Message msg;
    if (!sd::parse(p, len, &msg)) return;

    for (const auto& offer : msg.offers) {
        if (offer.service != cfg.serviceId) continue;
        if (cfg.instanceId != 0xFFFF && offer.instance != cfg.instanceId) continue;
        if (offer.majorVersion != cfg.majorVersion) {
            // A major version mismatch is not a near miss: the payload layouts
            // are allowed to differ completely, so speaking to it would decode
            // whatever arrives as if it were ours.
            LOGW("ignoring offer for %04x major %u (we speak %u)", offer.service,
                 offer.majorVersion, cfg.majorVersion);
            continue;
        }

        if (offer.isStop()) {
            dropPeer("peer sent a stop offer");
            continue;
        }
        if (!offer.udp.valid()) {
            LOGW("offer for %04x carried no UDP endpoint; events have nowhere to go",
                 offer.service);
            continue;
        }

        bool isNew;
        {
            std::lock_guard<std::mutex> lock(mu);
            isNew = udpEp.addressBe != offer.udp.addressBe || udpEp.port != offer.udp.port;
            udpEp = offer.udp;
            if (offer.tcp.valid()) tcpEp = offer.tcp;
        }
        if (isNew) {
            LOGI("service %04x.%04x at %s udp/%u tcp/%u", offer.service, offer.instance,
                 ipToString(offer.udp.addressBe).c_str(), offer.udp.port, offer.tcp.port);
            setState(LinkState::kOffered);
        }
        // Re-subscribing on every offer is what keeps the subscription alive
        // across a peer restart: the peer forgets its subscriber table, and
        // its next cyclic offer is the only notification we get of that.
        sendSubscribe(cfg.subscribeTtlSec);
    }

    for (const auto& ack : msg.acks) {
        if (ack.service != cfg.serviceId || ack.eventgroup != cfg.eventgroupId) continue;
        if (ack.isNack()) {
            LOGW("subscribe to %04x eventgroup %04x was refused", ack.service, ack.eventgroup);
            setState(LinkState::kOffered);
        } else {
            setState(LinkState::kSubscribed);
        }
    }
}

void MotorLink::Impl::handleEvent(const uint8_t* p, size_t len) {
    Header h;
    if (!decodeHeader(p, len, &h)) return;
    if (h.service != cfg.serviceId || h.method != cfg.eventId) return;
    if (h.messageType != kNotification) return;
    if (h.interfaceVersion != cfg.majorVersion) return;

    MotorEvent e;
    if (!decodeMotorEvent(p + kHeaderSize, h.payloadSize(), &e)) {
        LOGW("event payload rejected (%zu bytes)", h.payloadSize());
        return;
    }
    if (onEvent) onEvent(e);
}

void MotorLink::Impl::run() {
    std::vector<uint8_t> buf(kDatagramBuf);

    auto nextFind = Clock::now();
    auto nextRenew = Clock::time_point::max();

    while (running.load()) {
        if (refind.exchange(false)) {
            dropPeer("reconnect requested");
            nextFind = Clock::now();
            nextRenew = Clock::time_point::max();
        }

        const auto now = Clock::now();
        bool havePeer;
        {
            std::lock_guard<std::mutex> lock(mu);
            havePeer = udpEp.valid();
        }

        if (!havePeer && now >= nextFind) {
            sendFind();
            // One second between finds: fast enough that plugging the Pi in
            // shows data within a couple of seconds, slow enough that a head
            // unit left running next to nothing is not a broadcast source.
            nextFind = now + Ms(1000);
        }
        if (havePeer && now >= nextRenew) {
            sendSubscribe(cfg.subscribeTtlSec);
            nextRenew = now + Ms(cfg.subscribeTtlSec * 1000 / 2);
        }
        if (havePeer && nextRenew == Clock::time_point::max()) {
            nextRenew = now + Ms(cfg.subscribeTtlSec * 1000 / 2);
        }

        const auto deadline = havePeer ? nextRenew : nextFind;
        auto waitMs = std::chrono::duration_cast<Ms>(deadline - Clock::now()).count();
        if (waitMs < 0) waitMs = 0;
        if (waitMs > 1000) waitMs = 1000;

        pollfd fds[3];
        int n = 0;
        const int sdIdx = sdFd >= 0 ? n : -1;
        if (sdFd >= 0) fds[n++] = {sdFd, POLLIN, 0};
        const int evIdx = n;
        fds[n++] = {evFd, POLLIN, 0};
        const int wakeIdx = n;
        fds[n++] = {wakeFd[0], POLLIN, 0};

        const int ready = poll(fds, n, static_cast<int>(waitMs));
        if (ready < 0) {
            if (errno == EINTR) continue;
            LOGE("poll: %s", strerror(errno));
            break;
        }
        if (ready == 0) continue;

        if (sdIdx >= 0 && (fds[sdIdx].revents & POLLIN)) {
            for (;;) {
                const ssize_t got = recv(sdFd, buf.data(), buf.size(), 0);
                if (got <= 0) break;
                handleSd(buf.data(), static_cast<size_t>(got));
            }
        }
        if (fds[evIdx].revents & POLLIN) {
            for (;;) {
                const ssize_t got = recv(evFd, buf.data(), buf.size(), 0);
                if (got <= 0) break;
                handleEvent(buf.data(), static_cast<size_t>(got));
            }
        }
        if (fds[wakeIdx].revents & POLLIN) {
            uint8_t drain[64];
            while (read(wakeFd[0], drain, sizeof drain) > 0) {
            }
        }
    }

    // Tell the peer to stop sending into a socket that is about to close. Best
    // effort: if it does not arrive, the subscription lapses on its own TTL.
    sendSubscribe(0);
}

std::unique_ptr<MotorLink> MotorLink::start(const MotorLinkConfig& config, EventFn onEvent,
                                            StateFn onState) {
    auto impl = std::make_unique<Impl>();
    impl->cfg = config;
    impl->onEvent = std::move(onEvent);
    impl->onState = std::move(onState);

    if (!impl->openSockets()) return nullptr;

    if (!config.staticHost.empty()) {
        sd::Endpoint udp;
        sd::Endpoint tcp;
        if (inet_pton(AF_INET, config.staticHost.c_str(), &udp.addressBe) == 1) {
            udp.port = config.staticUdpPort;
            udp.protocol = sd::kUdp;
            tcp.addressBe = udp.addressBe;
            tcp.port = config.staticTcpPort;
            tcp.protocol = sd::kTcp;
            impl->udpEp = udp;
            impl->tcpEp = tcp;
            impl->state = LinkState::kOffered;
            LOGI("static peer %s udp/%u tcp/%u", config.staticHost.c_str(), udp.port, tcp.port);
        } else {
            LOGE("static host %s is not an IPv4 address", config.staticHost.c_str());
        }
    }

    LOGI("link up: service %04x.%04x v%u, events on :%u, sd %s:%u", config.serviceId,
         config.instanceId, config.majorVersion, impl->eventPort,
         config.sdMulticast.empty() ? "(disabled)" : config.sdMulticast.c_str(), config.sdPort);

    auto link = std::unique_ptr<MotorLink>(new MotorLink());
    link->impl_ = std::move(impl);
    link->impl_->running.store(true);
    link->impl_->thread = std::thread([raw = link->impl_.get()] { raw->run(); });
    return link;
}

MotorLink::~MotorLink() {
    if (!impl_) return;
    cancelCapture();
    impl_->running.store(false);
    impl_->wake();
    if (impl_->thread.joinable()) impl_->thread.join();
}

void MotorLink::reconnect() {
    if (!impl_) return;
    impl_->refind.store(true);
    impl_->wake();
}

void MotorLink::cancelCapture() {
    if (!impl_) return;
    impl_->captureCancel.store(true);
    const int fd = impl_->captureFd.load();
    if (fd >= 0) {
        // shutdown, not close: the owning thread is inside poll/recv on this
        // descriptor and closing it underneath would race with a reuse of the
        // number. shutdown wakes it with EOF and it closes its own fd.
        shutdown(fd, SHUT_RDWR);
    }
}

namespace {

// Reads exactly `want` bytes, or fails. Returns a CaptureError.
int readExact(int fd, uint8_t* dst, size_t want, Clock::time_point deadline,
              const std::atomic<bool>& cancel) {
    size_t got = 0;
    while (got < want) {
        if (cancel.load()) return kErrCancelled;

        auto left = std::chrono::duration_cast<Ms>(deadline - Clock::now()).count();
        if (left <= 0) return kErrTimeout;
        if (left > 250) left = 250;  // so cancellation is noticed promptly

        pollfd p{fd, POLLIN, 0};
        const int ready = poll(&p, 1, static_cast<int>(left));
        if (ready < 0) {
            if (errno == EINTR) continue;
            return kErrIo;
        }
        if (ready == 0) continue;
        if (p.revents & (POLLERR | POLLHUP | POLLNVAL)) return kErrIo;

        const ssize_t n = recv(fd, dst + got, want - got, 0);
        if (n == 0) return kErrIo;  // peer closed mid-transfer
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) continue;
            return kErrIo;
        }
        got += static_cast<size_t>(n);
    }
    return 0;
}

int connectWithTimeout(int fd, const sockaddr_in& dst, int timeoutMs) {
    if (connect(fd, reinterpret_cast<const sockaddr*>(&dst), sizeof dst) == 0) return 0;
    if (errno != EINPROGRESS) return kErrConnect;

    pollfd p{fd, POLLOUT, 0};
    const int ready = poll(&p, 1, timeoutMs);
    if (ready <= 0) return kErrConnect;

    int err = 0;
    socklen_t len = sizeof err;
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len) != 0 || err != 0) return kErrConnect;
    return 0;
}

}  // namespace

MotorLink::CaptureResult MotorLink::requestCapture(float requestedDurationSec) {
    CaptureResult result;

    if (!impl_) {
        result.status = kErrNoEndpoint;
        return result;
    }
    if (impl_->captureBusy.exchange(true)) {
        // Upstairs only ever has one in flight; arriving here means two
        // callers, and interleaving them on one socket would corrupt both.
        LOGW("capture requested while one was already running");
        result.status = kErrIo;
        return result;
    }
    struct BusyGuard {
        std::atomic<bool>& flag;
        ~BusyGuard() { flag.store(false); }
    } busyGuard{impl_->captureBusy};

    impl_->captureCancel.store(false);

    sd::Endpoint peer;
    {
        std::lock_guard<std::mutex> lock(impl_->mu);
        peer = impl_->tcpEp;
    }
    if (!peer.valid()) {
        result.status = kErrNoEndpoint;
        return result;
    }

    const int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        result.status = kErrConnect;
        return result;
    }
    struct FdGuard {
        int fd;
        std::atomic<int>& slot;
        ~FdGuard() {
            slot.store(-1);
            close(fd);
        }
    } fdGuard{fd, impl_->captureFd};
    impl_->captureFd.store(fd);

    bindToNetwork(fd, impl_->cfg.androidNetworkHandle, "capture socket");
    // Same reasoning as the multicast egress pin in openSockets(): pinning the
    // interface does not pin which of its addresses connect() uses as source,
    // and a TCP handshake sourced from the wrong one on eth0 fails silently
    // from here -- SYN leaves, the diagnostics unit has no route back to it,
    // and this socket just times out looking like the unit never answered.
    if (const uint32_t lan = localAddressOnLan(); lan != 0) {
        sockaddr_in src{};
        src.sin_family = AF_INET;
        src.sin_addr.s_addr = lan;
        if (bind(fd, reinterpret_cast<sockaddr*>(&src), sizeof src) != 0) {
            LOGW("capture socket: bind to LAN address failed (%s)", strerror(errno));
        }
    }
    setNonBlocking(fd);
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);

    sockaddr_in dst{};
    dst.sin_family = AF_INET;
    dst.sin_addr.s_addr = peer.addressBe;
    dst.sin_port = htons(peer.port);

    const auto deadline = Clock::now() + Ms(impl_->cfg.captureTimeoutMs);
    const int64_t startedMs = nowMs();

    if (const int err = connectWithTimeout(fd, dst, kConnectTimeoutMs); err != 0) {
        LOGW("capture connect to %s:%u failed", ipToString(peer.addressBe).c_str(), peer.port);
        result.status = impl_->captureCancel.load() ? kErrCancelled : err;
        return result;
    }

    Header req;
    req.service = impl_->cfg.serviceId;
    req.method = impl_->cfg.captureMethodId;
    req.length = static_cast<uint32_t>(kLengthPrefix + kCaptureRequestSize);
    req.client = impl_->cfg.clientId;
    req.session = impl_->captureSession.fetch_add(1);
    if (req.session == 0) req.session = 1;
    req.protocolVersion = 0x01;
    req.interfaceVersion = impl_->cfg.majorVersion;
    req.messageType = kRequest;
    req.returnCode = kOk;

    uint8_t out[kHeaderSize + kCaptureRequestSize];
    encodeHeader(req, out);
    encodeCaptureRequest(requestedDurationSec, out + kHeaderSize);

    size_t sent = 0;
    while (sent < sizeof out) {
        const ssize_t n = send(fd, out + sent, sizeof out - sent, MSG_NOSIGNAL);
        if (n > 0) {
            sent += static_cast<size_t>(n);
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) continue;
        result.status = kErrIo;
        return result;
    }

    uint8_t head[kHeaderSize];
    if (const int err = readExact(fd, head, sizeof head, deadline, impl_->captureCancel);
        err != 0) {
        result.status = err;
        return result;
    }

    // Read the fields directly rather than through decodeHeader: that function
    // insists the payload it describes is already in the buffer, which is true
    // of a datagram and never true of the first 16 bytes off a stream.
    Header resp;
    resp.service = readU16(head + 0);
    resp.method = readU16(head + 2);
    resp.length = readU32(head + 4);
    resp.session = readU16(head + 10);
    resp.messageType = head[14];
    resp.returnCode = head[15];

    if (resp.service != impl_->cfg.serviceId || resp.method != impl_->cfg.captureMethodId ||
        resp.session != req.session) {
        LOGW("capture reply mismatched: %04x.%04x session %u", resp.service, resp.method,
             resp.session);
        result.status = kErrMalformed;
        return result;
    }
    if (resp.messageType == kError || resp.returnCode != kOk) {
        LOGW("capture rejected by peer: return code %u", resp.returnCode);
        result.status = kErrMalformed;
        return result;
    }
    if (resp.length < kLengthPrefix || resp.length - kLengthPrefix > kMaxCapturePayload) {
        LOGW("capture response claims %u bytes", resp.length);
        result.status = kErrMalformed;
        return result;
    }

    std::vector<uint8_t> payload(resp.length - kLengthPrefix);
    if (const int err = readExact(fd, payload.data(), payload.size(), deadline,
                                  impl_->captureCancel);
        err != 0) {
        result.status = err;
        return result;
    }

    CaptureHeader ch;
    if (!decodeCaptureHeader(payload.data(), payload.size(), &ch)) {
        LOGW("capture payload (%zu bytes) is not a layout-%u capture", payload.size(),
             kLayoutVersion);
        result.status = kErrMalformed;
        return result;
    }
    result.header = ch;

    if (ch.status != kCaptureOk) {
        result.status = ch.status;
        return result;
    }

    result.samples.resize(static_cast<size_t>(ch.channelCount) * ch.sampleCount);
    if (!decodeCaptureSamples(payload.data(), payload.size(), ch, result.samples.data())) {
        LOGW("capture samples rejected (ragged or non-finite)");
        result.samples.clear();
        result.status = kErrBadSamples;
        return result;
    }

    LOGI("capture: %u channels x %u samples @ %.0f Hz, %zu-byte header, %lld ms", ch.channelCount,
         ch.sampleCount, ch.sampleRateHz, ch.headerSize,
         static_cast<long long>(nowMs() - startedMs));
    result.status = 0;
    return result;
}

}  // namespace motorguard::someip
