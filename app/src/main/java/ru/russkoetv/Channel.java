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
    /** Адрес видеопотока. */
    final String url;
    /** Путь к логотипу: сначала ищется в приложении, потом на сервере обновлений. */
    final String logo;
    /** Код канала в телепрограмме, может отсутствовать. */
    final String epg;
    /** Если задан — канал без открытого потока, открывается официальная страница с плеером. */
    final String page;
    /** CSS-селектор плеера на этой странице — его показываем во весь экран. */
    final String player;

    private Channel(JSONObject json) throws JSONException {
        id = json.getString("id");
        name = json.getString("name");
        url = json.optString("url", "");
        logo = json.optString("logo", null);
        epg = json.isNull("epg") ? null : json.optString("epg", null);
        page = json.optString("page", null);
        player = json.optString("player", null);
    }

    boolean isWebPage() {
        return page != null && !page.isEmpty();
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
