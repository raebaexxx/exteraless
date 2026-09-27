package app.exteraless.reels.ui;

import android.view.TextureView;
import android.widget.FrameLayout;

import androidx.media3.common.Player;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.Components.VideoPlayer;

import java.util.ArrayList;

import app.exteraless.reels.ReelsConfig;
import app.exteraless.reels.ReelsMediaPolicy;

/**
 * Плеер «Клипов»: один {@link VideoPlayer} на весь экран, который переезжает на текущую
 * страницу вертикальной ленты.
 *
 * <p>Своего плеера на страницу не заводим намеренно: ExoPlayer с аудиофокусом тяжёлый, а
 * в вертикальной ленте одновременно играет ровно один ролик. Соседние страницы держат
 * только постеры, поэтому перелистывание не создаёт и не уничтожает плееры.
 *
 * <p>Про {@code NotificationCenter.playerDidStartPlaying}: {@code VideoPlayer} шлёт его
 * только когда звук включён, поэтому при входе во вкладку и при снятии мута чужие
 * проигрыватели гасим явно — иначе можно получить два звука сразу.
 */
public class ReelsPlayerController {

    public interface Callback {
        void onVideoSize(int width, int height, int rotation, float pixelWidthHeightRatio);

        void onRenderedFirstFrame();

        void onStateChanged(boolean playing, boolean buffering);

        void onError();
    }

    private final int currentAccount;

    private VideoPlayer player;
    private TextureView textureView;
    private FrameLayout container;
    private MessageObject playingMessage;
    private Callback callback;
    private ArrayList<VideoPlayer.Quality> qualities;

    private boolean muted;
    private boolean released;
    private boolean firstFrameRendered;
    private boolean prepared;

    public ReelsPlayerController(int currentAccount) {
        this.currentAccount = currentAccount;
        this.muted = ReelsConfig.getInstance(currentAccount).getStartMuted();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public boolean isMuted() {
        return muted;
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying() && playingMessage != null;
    }

    public boolean isPlayingMessage(MessageObject message) {
        return playingMessage != null && message != null && playingMessage == message;
    }

    public MessageObject getPlayingMessage() {
        return playingMessage;
    }

    public long getPosition() {
        return player != null ? player.getCurrentPosition() : 0;
    }

    public long getDuration() {
        return player != null ? player.getDuration() : 0;
    }

    public float getBufferedPercentage() {
        return player != null ? player.getBufferedPercentage() : 0f;
    }

    public ArrayList<VideoPlayer.Quality> getQualities() {
        return qualities;
    }

    /**
     * Переводит плеер на другую страницу. Возвращает false, если пост нечем играть
     * (например, это фотография) — тогда вызывающий просто оставляет постер.
     */
    public boolean attach(MessageObject message, TextureView newTextureView, FrameLayout newContainer) {
        if (message == null || !ReelsMediaPolicy.isPlayable(message)) {
            detach();
            return false;
        }
        if (player == null || released) {
            createPlayer();
        }
        playingMessage = message;
        textureView = newTextureView;
        container = newContainer;
        firstFrameRendered = false;
        prepared = false;
        if (textureView != null) {
            player.setTextureView(textureView);
        }
        player.setLooping(true);
        player.setMute(muted);
        player.handleAudioFocus(true);
        qualities = VideoPlayer.getQualities(currentAccount, message.messageOwner.media, true);
        if (qualities == null || qualities.isEmpty()) {
            detach();
            notifyError();
            return false;
        }
        player.preparePlayer(qualities, VideoPlayer.getSavedQuality(qualities, message));
        prepared = true;
        player.play();
        return true;
    }

    private void createPlayer() {
        // pauseOther = true: два ролика одновременно не играем.
        player = new VideoPlayer();
        player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
                if (callback != null) {
                    callback.onStateChanged(playWhenReady && playbackState == Player.STATE_READY,
                            playbackState == Player.STATE_BUFFERING);
                }
            }

            @Override
            public void onError(VideoPlayer videoPlayer, Exception e) {
                FileLog.e(e);
                notifyError();
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                if (callback != null) {
                    callback.onVideoSize(width, height, unappliedRotationDegrees, pixelWidthHeightRatio);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                firstFrameRendered = true;
                if (callback != null) {
                    callback.onRenderedFirstFrame();
                }
            }
        });
        released = false;
    }

    private void notifyError() {
        if (callback != null) {
            callback.onError();
        }
    }

    /** Снимает звук и освобождает плеер, но сам экземпляр оставляет для следующего ролика. */
    public void detach() {
        if (player == null) {
            return;
        }
        player.pause();
        player.setMute(true);
        if (textureView != null) {
            player.setTextureView(null);
        }
        if (prepared) {
            player.releasePlayer(false);
        }
        prepared = false;
        firstFrameRendered = false;
        playingMessage = null;
        textureView = null;
        container = null;
    }

    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    public void play() {
        if (player != null && playingMessage != null) {
            player.play();
        }
    }

    public void setMuted(boolean value) {
        muted = value;
        if (player == null) {
            return;
        }
        if (!value) {
            // Снимаем мут — глушим всё, что играет сейчас: playerDidStartPlaying придёт
            // только от немаутого плеера и не защитит нас от чата или музыки.
            MediaController mediaController = MediaController.getInstance();
            if (mediaController.getPlayingMessageObject() != null) {
                mediaController.cleanupPlayer(true, true);
            }
        }
        player.setMute(value);
    }

    public void setQuality(int index) {
        if (player != null && playingMessage != null) {
            player.setSelectedQuality(index);
            VideoPlayer.saveQuality(player.getCurrentQuality(), playingMessage);
        }
    }

    /** Полностью освобождает плеер: при уходе со вкладки и при уничтожении экрана. */
    public void release() {
        detach();
        if (player != null) {
            player.setDelegate(null);
            player.releasePlayer(true);
            player = null;
        }
        released = true;
    }

    public boolean hasRenderedFirstFrame() {
        return firstFrameRendered;
    }

    FrameLayout getContainer() {
        return container;
    }
}
