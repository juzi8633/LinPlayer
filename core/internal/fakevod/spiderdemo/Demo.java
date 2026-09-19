package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;

import java.util.HashMap;
import java.util.List;

/**
 * 真机自检用的最小 TVBox jar 源(csp_Demo):验安卓 :spider 进程里 DexClassLoader 加载 + catvod 调用这条链。
 * 数据是编的;ext 是假站的根地址,播放地址落到假站的测试视频。
 * 编法见 scripts/build-spider-demo.sh(javac → d8 → 带 classes.dex 的 jar)。
 */
public class Demo extends Spider {
    private String base = "";

    @Override
    public void init(Context context, String extend) {
        base = extend == null ? "" : extend.trim();
        Proxy.base = base;
    }

    private String item(String id, String name) {
        return "{\"vod_id\":\"" + id + "\",\"vod_name\":\"" + name + "\",\"vod_pic\":\"" + base + "/media/poster-" + id + ".jpg\",\"vod_remarks\":\"jar\"}";
    }

    @Override
    public String homeContent(boolean filter) {
        return "{\"class\":[{\"type_id\":\"1\",\"type_name\":\"jar分类\"}],\"list\":[" + item("j1", "jar影片一") + "," + item("j2", "jar影片二") + "]}";
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return "{\"page\":1,\"pagecount\":1,\"list\":[" + item("j1", "jar影片一") + "]}";
    }

    @Override
    public String detailContent(List<String> ids) {
        String id = ids.isEmpty() ? "j1" : ids.get(0);
        return "{\"list\":[{\"vod_id\":\"" + id + "\",\"vod_name\":\"jar影片\",\"vod_play_from\":\"jar线路\",\"vod_play_url\":\"第1集$" + id + "-1#第2集$" + id + "-2\"}]}";
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return "{\"list\":[" + item("j1", "jar影片一") + "]}";
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // 第 2 集走宿主的本地代理(D353):地址指向 127.0.0.1:9978/proxy,由 Proxy.proxy 吐 m3u8
        if (id.endsWith("-2")) return "{\"parse\":0,\"url\":\"" + Proxy.url() + "?do=demo&id=" + id + "\"}";
        return "{\"parse\":0,\"url\":\"" + base + "/media/jar-" + id + ".m3u8\"}";
    }
}
