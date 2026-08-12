package io.github.jtplatform.delivery.publisher;

public enum PublishDisposition {
    ACCEPTED,
    DROPPED,
    /** The caller retains ownership of a critical message and must submit it again later. */
    RETRY_REQUIRED,
    DISABLED
}
