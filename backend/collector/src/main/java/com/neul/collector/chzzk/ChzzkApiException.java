package com.neul.collector.chzzk;

/**
 * Chzzk API 호출 실패 시 발생하는 비검사 예외.
 */
public class ChzzkApiException extends RuntimeException {
    public ChzzkApiException(String message) {
        super(message);
    }

    public ChzzkApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
