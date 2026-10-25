package ru.russkoetv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** Телепрограмма из epg.json: для каждого канала — передачи { начало, конец, название }. */
final class Epg {
    static final Epg EMPTY = new Epg();

    private final Map<String, long[]> starts = new HashMap<>();
    private final Map<String, long[]> stops = new HashMap<>();
    private final Map<String, String[]> titles = new HashMap<>();

    private Epg() {
    }

    static Epg parse(String json) throws JSONException {
        Epg epg = new Epg();
        JSONObject channels = new JSONObject(json).getJSONObject("channels");
        Iterator<String> keys = channels.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONArray items = channels.getJSONArray(key);
            long[] start = new long[items.length()];
            long[] stop = new long[items.length()];
            String[] title = new String[items.length()];
            for (int i = 0; i < items.length(); i++) {
                JSONArray item = items.getJSONArray(i);
                start[i] = item.getLong(0);
                stop[i] = item.getLong(1);
                title[i] = item.getString(2);
            }
            epg.starts.put(key, start);
            epg.stops.put(key, stop);
            epg.titles.put(key, title);
        }
        return epg;
    }

    /** «Сейчас: Новости · до 21:30» или null, если о канале ничего не известно. */
    String nowPlaying(String channel, long now) {
        if (channel == null || !starts.containsKey(channel)) {
            return null;
        }
        long[] start = starts.get(channel);
        long[] stop = stops.get(channel);
        for (int i = 0; i < start.length; i++) {
            if (start[i] <= now && now < stop[i]) {
                String until = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(stop[i]));
                return titles.get(channel)[i] + " · до " + until;
            }
        }
        return null;
    }
}
