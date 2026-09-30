package com.example.voiceafk;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;

public class VoiceAFK extends JavaPlugin {

    private volatile JDA jda;
    private BukkitTask checkTask;
    private long channelId;
    private boolean selfDeafen;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String token = getConfig().getString("token", "");
        String idStr = getConfig().getString("voice-channel-id", "");
        selfDeafen = getConfig().getBoolean("self-deafen", true);
        int interval = Math.max(10, getConfig().getInt("check-interval-seconds", 30));

        if (token.isBlank() || token.startsWith("DAN_TOKEN")) {
            getLogger().severe("Chưa điền token trong config.yml!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            channelId = Long.parseLong(idStr.trim());
        } catch (NumberFormatException e) {
            getLogger().severe("voice-channel-id không hợp lệ!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Chạy async để không làm đơ server khi đăng nhập
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                jda = JDABuilder.createLight(token, EnumSet.of(GatewayIntent.GUILD_VOICE_STATES))
                        .enableCache(CacheFlag.VOICE_STATE)
                        .setMemberCachePolicy(MemberCachePolicy.VOICE)
                        .addEventListeners(new ListenerAdapter() {
                            @Override
                            public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
                                // Bot bị kick/rớt khỏi voice -> vào lại sau 3 giây
                                if (event.getMember().getIdLong() == event.getJDA().getSelfUser().getIdLong()
                                        && event.getChannelJoined() == null) {
                                    getServer().getScheduler().runTaskLaterAsynchronously(
                                            VoiceAFK.this, VoiceAFK.this::ensureConnected, 60L);
                                }
                            }
                        })
                        .build();
                jda.awaitReady();
                getLogger().info("Bot đã đăng nhập: " + jda.getSelfUser().getName());
                ensureConnected();
            } catch (Exception e) {
                getLogger().severe("Không thể đăng nhập bot: " + e.getMessage());
            }
        });

        // Kiểm tra định kỳ, phòng khi rớt mạng
        long ticks = interval * 20L;
        checkTask = getServer().getScheduler().runTaskTimerAsynchronously(this, this::ensureConnected, ticks, ticks);
    }

    private void ensureConnected() {
        JDA api = jda;
        if (api == null || api.getStatus() != JDA.Status.CONNECTED) return;

        VoiceChannel channel = api.getVoiceChannelById(channelId);
        if (channel == null) {
            getLogger().warning("Không tìm thấy kênh voice " + channelId
                    + " (sai ID hoặc bot chưa ở trong server đó).");
            return;
        }

        AudioManager am = channel.getGuild().getAudioManager();
        am.setSelfDeafened(selfDeafen);

        if (!am.isConnected() || am.getConnectedChannel() == null
                || am.getConnectedChannel().getIdLong() != channelId) {
            try {
                am.openAudioConnection(channel);
                getLogger().info("Đã vào kênh voice: " + channel.getName());
            } catch (Exception e) {
                getLogger().warning("Không vào được voice: " + e.getMessage()
                        + " (kiểm tra quyền Connect/View Channel)");
            }
        }
    }

    @Override
    public void onDisable() {
        if (checkTask != null) checkTask.cancel();
        if (jda != null) {
            jda.shutdown();
        }
    }
}
