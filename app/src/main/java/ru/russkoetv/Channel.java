package ru.russkoetv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Канал из channels.json. */
final class Channel {
    /** Постоянный код канала — по нему запоминаются порядок и последний канал. */
    final String id;
    final String name;
    /** Адрес видеопотока; пустой, если поток берётся с RUTUBE. */
    final String url;
    /** Видео прямого эфира на RUTUBE (официальный аккаунт канала): адрес потока узнаём перед показом. */
    final String rutube;
    /** Путь к логотипу: сначала ищется в приложении, потом на сервере обновлений. */
    final String logo;
    /** Код канала в телепрограмме, может отсутствовать. */
    final String epg;

    private Channel(JSONObject json) throws JSONException {
        id = json.getString("id");
        name = json.getString("name");
        url = json.optString("url", "");
        rutube = json.optString("rutube", null);
        logo = json.optString("logo", null);
        epg = json.isNull("epg") ? null : json.optString("epg", null);
    }

    static List<Channel> parse(String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        if (array.length() == 0) {
            throw new JSONException("Пустой список каналов");
        }
        List<Channel> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            result.add(new Channel(array.getJSONObject(i)));
        }
        return result;
    }
}
