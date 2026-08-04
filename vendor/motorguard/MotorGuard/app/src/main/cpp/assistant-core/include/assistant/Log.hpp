#pragma once

#include <cstdio>
#include <mutex>
#include <string>

// Dependency-free logging. Keeps the core portable: on Android an adapter can
// redirect these to logcat by defining ASSISTANT_LOG_SINK before including, but
// the default stderr sink works everywhere.

namespace assistant {

enum class LogLevel { Debug = 0, Info = 1, Warn = 2, Error = 3 };

class Log {
public:
    static LogLevel& level() {
        static LogLevel lvl = LogLevel::Info;
        return lvl;
    }

    template <typename... Args>
    static void write(LogLevel lvl, const char* tag, const char* fmt, Args... args) {
        if (lvl < level()) return;
        static std::mutex m;
        std::lock_guard<std::mutex> lock(m);
        std::fprintf(stderr, "[%s] %-5s ", tag, name(lvl));
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wformat-security"
        std::fprintf(stderr, fmt, args...);
#pragma GCC diagnostic pop
        std::fprintf(stderr, "\n");
    }

private:
    static const char* name(LogLevel l) {
        switch (l) {
            case LogLevel::Debug: return "DEBUG";
            case LogLevel::Info:  return "INFO";
            case LogLevel::Warn:  return "WARN";
            case LogLevel::Error: return "ERROR";
        }
        return "?";
    }
};

}  // namespace assistant

#define LOG_D(tag, ...) ::assistant::Log::write(::assistant::LogLevel::Debug, tag, __VA_ARGS__)
#define LOG_I(tag, ...) ::assistant::Log::write(::assistant::LogLevel::Info,  tag, __VA_ARGS__)
#define LOG_W(tag, ...) ::assistant::Log::write(::assistant::LogLevel::Warn,  tag, __VA_ARGS__)
#define LOG_E(tag, ...) ::assistant::Log::write(::assistant::LogLevel::Error, tag, __VA_ARGS__)
