package lightning.dsp;

@Backing(type="int")
enum ErrorCode {
    OK = 0,
    UNSUPPORTED_VERSION = 1,
    NO_LIVE_CONTEXTS = 2,
    STALE_CONTEXT = 3,
    UNAUTHORIZED = 4,
    CONFLICT = 5,
    INVALID_ARGUMENT = 6,
    BUSY = 7,
    REALTIME_GATE = 8,
    CLOSED = 9,
    REGISTRATION_GATE = 10,
    PENDING = 11,
    CONFIGURATION_CHANGED = 12,
    NO_MEMORY = 13
}
