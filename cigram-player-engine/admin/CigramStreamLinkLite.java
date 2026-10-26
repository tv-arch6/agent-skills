package com.my.newproject;

/** The admin stores plain links; a link written as "url|Header=value" is cut to its url part, as the viewer app does. */
final class CigramStreamLinkLite {
    private CigramStreamLinkLite() { }
    static String clean(String url) {
        if (url == null) return "";
        int bar = url.indexOf('|');
        return (bar >= 0 ? url.substring(0, bar) : url).trim();
    }
}
