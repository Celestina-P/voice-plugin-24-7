package com.example.voiceafk;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
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
    private volatile long lastAttempt = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String token = getConfig().getString("token", "");
        String idStr = getConfig().getString("voice-channel-id", "");
        selfDeafen = getConfig().getBoolean("self-deafen", true);
        int interval = Math.max(15, getConfig().getInt("check-interval-seconds", 30));

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

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                jda = JDABuilder.createLight(token, EnumSet.of(GatewayIntent.GUILD_VOICE_STATES))
                        .enableCache(CacheFlag.VOICE_STATE)
                        .setMemberCachePolicy(MemberCachePolicy.VOICE)
                        // Discord bắt buộc mã hóa DAVE cho voice, không có sẽ bị đóng kết nối (mã 4017)
                        .setAudioModuleConfig(new AudioModuleConfig()
                                .withDaveSessionFactory(new JDaveSessionFactory()))
                        .build();
                jda.awaitReady();
                getLogger().info("Bot đã đăng nhập: " + jda.getSelfUser().getName());
                ensureConnected();
            } catch (Throwable e) {
                getLogger().severe("Không thể đăng nhập bot: " + e);
            }
        });

        // Chỉ kiểm tra định kỳ, KHÔNG vào lại ngay khi rớt để tránh vòng lặp ra/vào
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

        // Đang kết nối thì đừng gọi lại
        if (am.isAttemptingToConnect()) return;

        boolean inRightChannel = am.isConnected()
                && am.getConnectedChannel() != null
                && am.getConnectedChannel().getIdLong() == channelId;
        if (inRightChannel) return;

        // Cách nhau ít nhất 10 giây giữa các lần thử
        long now = System.currentTimeMillis();
        if (now - lastAttempt < 10_000) return;
        lastAttempt = now;

        try {
            am.openAudioConnection(channel);
            getLogger().info("Đang vào kênh voice: " + channel.getName());
        } catch (Exception e) {
            getLogger().warning("Không vào được voice: " + e.getMessage()
                    + " (kiểm tra quyền Connect/View Channel)");
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
