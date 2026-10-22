package dev.kizuna.meridian.human.ui.smoke;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundInstanceListener;
import net.minecraft.client.sound.WeightedSoundSet;

import java.util.ArrayList;
import java.util.List;

/** 在真实声音引擎中检查残卷播放次数、正文音量和切屏后的资源释放。 */
final class ScrollAudioCheck implements SoundInstanceListener {
    private final List<SoundInstance> played = new ArrayList<>();

    void begin(MinecraftClient client) {
        // 每次正式右键前清空观察记录，监听器只读，不能替界面播放或停止声音。
        client.getSoundManager().unregisterListener(this);
        played.clear();
        client.getSoundManager().registerListener(this);
    }

    @Override
    public void onSoundPlayed(SoundInstance sound, WeightedSoundSet set) {
        // 忽略世界脚步等无关声音；记录实际解析到的素材，资源缺失不能误报通过。
        if (!sound.getId().getNamespace().equals("meridian_progression")) {
            return;
        }
        played.add(sound);
        System.out.println("SCROLL_AUDIO_PLAY " + sound.getId().getPath()
                + " loop=" + sound.isRepeatable() + " at=" + System.currentTimeMillis());
    }

    void body(MinecraftClient client) {
        // 正文阶段应只剩一个低风声源，展开纸声结束，resize 不会再开一组声音。
        require(names().equals(List.of("paper_rustle", "low_wind")), "正文重复纸声或风声");
        SoundInstance paper = played.get(0);
        SoundInstance wind = played.get(1);
        require(!paper.isRepeatable() && wind.isRepeatable(), "纸声/风声循环标记错误");
        require(!client.getSoundManager().isPlaying(paper), "正文仍在播放纸声");
        require(client.getSoundManager().isPlaying(wind), "低风没有实际声源");
        require(Math.abs(wind.getVolume() - .05f) < .00001f, "正文风声没有降低至 0.05");
    }

    void ended(MinecraftClient client, boolean complete) {
        // 等声音引擎回收异步停止的声源后检查；关卷不补播，正常读完只多一次合拢声。
        List<String> expected = complete
                ? List.of("paper_rustle", "low_wind", "paper_close")
                : List.of("paper_rustle", "low_wind");
        require(names().equals(expected), "开合纸声次数错误：" + names());
        for (SoundInstance sound : played) {
            require(!client.getSoundManager().isPlaying(sound), "切屏后残留声音：" + sound.getId());
            if (!sound.getId().getPath().equals("low_wind")) {
                require(!sound.isRepeatable(), "纸声仍然循环");
            }
        }
        client.getSoundManager().unregisterListener(this);
        System.out.println("SCROLL_AUDIO_" + (complete ? "COMPLETE" : "ESC") + "_OK at=" + System.currentTimeMillis());
    }

    private List<String> names() {
        // 保留调用顺序，重复播放同一素材也会进入断言。
        return played.stream().map(sound -> sound.getId().getPath()).toList();
    }

    private static void require(boolean condition, String message) {
        // 音效失败中止整段阅读验收，不影响正式游戏的播放逻辑。
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
