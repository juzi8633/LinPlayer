package com.github.catvod.spider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Scanner;

/**
 * TVBox jar 约定的本地代理入口(宿主在 127.0.0.1:9978/proxy 转过来)。
 * 自检用:取假站的 m3u8,把相对分片改成绝对地址再吐回去 —— 真实 jar 用它做解密、改写、拼接。
 */
public class Proxy {
    public static int port = 9978;
    static String base = "";

    public static String url() {
        return "http://127.0.0.1:" + port + "/proxy";
    }

    public static Object[] proxy(Map<String, String> params) throws Exception {
        if (!"demo".equals(params.get("do"))) return null;
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/media/jar-proxy-" + params.get("id") + ".m3u8").openConnection();
        String text;
        try (InputStream in = c.getInputStream(); Scanner sc = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            text = sc.hasNext() ? sc.next() : "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            String l = line.trim();
            out.append(!l.isEmpty() && !l.startsWith("#") && !l.startsWith("http") ? base + "/media/" + l : l).append('\n');
        }
        return new Object[]{200, "application/vnd.apple.mpegurl", new ByteArrayInputStream(out.toString().getBytes("UTF-8"))};
    }
}
