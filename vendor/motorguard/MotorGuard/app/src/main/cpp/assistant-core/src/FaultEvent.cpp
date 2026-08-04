#include "assistant/FaultEvent.hpp"

namespace assistant {

const char* toString(Severity s) noexcept {
    switch (s) {
        case Severity::Info:     return "Info";
        case Severity::Advisory: return "Advisory";
        case Severity::Soon:     return "Soon";
        case Severity::Urgent:   return "Urgent";
        case Severity::StopNow:  return "StopNow";
    }
    return "?";
}

const char* toString(FaultSource s) noexcept {
    switch (s) {
        case FaultSource::ActiveDtc: return "ActiveDtc";
        case FaultSource::Predicted: return "Predicted";
    }
    return "?";
}

}  // namespace assistant
