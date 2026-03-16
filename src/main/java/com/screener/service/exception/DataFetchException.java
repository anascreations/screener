//package com.screener.service.exception;
//
//public class DataFetchException extends RuntimeException {
//	 
//    private final boolean retryable;
// 
//    /** Transient error — will be retried. */
//    public DataFetchException(String message) {
//        super(message);
//        this.retryable = true;
//    }
// 
//    /** Transient error with root cause — will be retried. */
//    public DataFetchException(String message, Throwable cause) {
//        super(message, cause);
//        this.retryable = true;
//    }
// 
//    /** Permanent error — will NOT be retried. */
//    public static DataFetchException permanent(String message) {
//        return new DataFetchException(message, false);
//    }
// 
//    private DataFetchException(String message, boolean retryable) {
//        super(message);
//        this.retryable = retryable;
//    }
// 
//    public boolean isRetryable() {
//        return retryable;
//    }
//}
// 