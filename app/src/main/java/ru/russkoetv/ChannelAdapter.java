package ru.russkoetv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/** Строка списка: номер, логотип, название и передача, которая идёт сейчас. */
final class ChannelAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final LogoLoader logos;
    private List<Channel> channels;
    private Epg epg = Epg.EMPTY;
    /** Канал, который сейчас переставляют, или -1. */
    private int moving = -1;

    ChannelAdapter(LayoutInflater inflater, LogoLoader logos, List<Channel> channels) {
        this.inflater = inflater;
        this.logos = logos;
        this.channels = channels;
    }

    void setChannels(List<Channel> channels) {
        this.channels = channels;
        notifyDataSetChanged();
    }

    void setEpg(Epg epg) {
        this.epg = epg;
        notifyDataSetChanged();
    }

    void setMoving(int position) {
        moving = position;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return channels.size();
    }

    @Override
    public Channel getItem(int position) {
        return channels.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View view, ViewGroup parent) {
        if (view == null) {
            view = inflater.inflate(R.layout.item_channel, parent, false);
        }
        Channel channel = channels.get(position);
        ((TextView) view.findViewById(R.id.number)).setText(String.valueOf(position + 1));
        ((TextView) view.findViewById(R.id.name)).setText(channel.name);
        logos.load(channel.logo, (ImageView) view.findViewById(R.id.logo));

        TextView programme = view.findViewById(R.id.programme);
        String now = epg.nowPlaying(channel.epg, System.currentTimeMillis());
        programme.setText(now);
        programme.setVisibility(now == null ? View.GONE : View.VISIBLE);

        view.setBackgroundResource(position == moving ? R.drawable.item_moving : R.drawable.item_current);
        return view;
    }
}
