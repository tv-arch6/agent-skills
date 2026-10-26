package com.Cigram.vid;

/**
 * One error type for the whole player engine (catalog, providers, validator, subtitles).
 * Carries a machine-readable {@link #kind} and an Arabic message that is safe to show to the viewer
 * (never contains URLs, tokens or headers).
 */
public final class CigramEngineException extends Exception {
    public static final int CANCELLED = 1;
    public static final int TIMEOUT = 2;
    public static final int NETWORK = 3;
    public static final int HTTP = 4;
    public static final int AUTH = 5;
    public static final int PARSE = 6;
    public static final int UNSUPPORTED = 7;
    public static final int NOT_CONFIGURED = 8;
    public static final int NOT_FOUND = 9;
    public static final int DRM = 10;
    public static final int NOT_MEDIA = 11;

    public final int kind;
    public final int httpCode;

    public CigramEngineException(int kind, String userMessage) {
        this(kind, 0, userMessage, null);
    }

    public CigramEngineException(int kind, int httpCode, String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.kind = kind;
        this.httpCode = httpCode;
    }

    /** True when asking again later can reasonably succeed. */
    public boolean retryable() {
        if (kind == TIMEOUT || kind == NETWORK) return true;
        if (kind == HTTP) return httpCode == 408 || httpCode == 425 || httpCode == 429 || httpCode >= 500;
        return false;
    }

    public static CigramEngineException cancelled() {
        return new CigramEngineException(CANCELLED, "أُلغيت العملية.");
    }

    public static CigramEngineException http(int code) {
        String m;
        if (code == 401 || code == 403) m = "المصدر يرفض الوصول (" + code + ").";
        else if (code == 404 || code == 410) m = "الملف غير موجود على المصدر (" + code + ").";
        else if (code == 429) m = "المصدر يرفض الطلبات الكثيرة حالياً (429).";
        else if (code >= 500) m = "خادم المصدر لا يستجيب الآن (" + code + ").";
        else m = "ردّ غير متوقع من المصدر (" + code + ").";
        int kind = (code == 401 || code == 403) ? AUTH : (code == 404 || code == 410) ? NOT_FOUND : HTTP;
        return new CigramEngineException(kind, code, m, null);
    }
}
