package app.exteraless.reels;

import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;

/**
 * Что попадает в «Клипы» и какими типами медиа искать это в базе.
 *
 * <p>Ключевой момент: фильтровать по колонке {@code messages_v2.media} нельзя — туда
 * пишется счётчик просмотров, а у постов канала он есть почти всегда
 * ({@code MessagesStorage.java:12762}). Поэтому выборка идёт по таблице {@code media_v4},
 * где типы честные, а индекс {@code uid_type_date_mid_idx_media_v4} подходит под запрос.
 *
 * <p>Типы в {@code media_v4} (см. {@code MediaDataController.getMediaType}):
 * {@code 0} — фото и видео-документы, {@code 2} — круглые видео и голосовые,
 * {@code 5} — анимированные документы (GIF), {@code 1} — обычные файлы,
 * {@code 3} — посты со ссылкой, {@code 4} — музыка. Стикеры в таблицу не попадают.
 * Тип {@code 2} в канале — это круглое видео: голосовых в каналах не бывает, а лишнее
 * всё равно отсекается проверкой {@link MessageObject#isRoundVideo()} после разбора.
 */
public final class ReelsMediaPolicy {

    private static final int TYPE_PHOTOVIDEO = MediaDataController.MEDIA_PHOTOVIDEO;
    private static final int TYPE_AUDIO = MediaDataController.MEDIA_AUDIO;
    private static final int TYPE_GIF = MediaDataController.MEDIA_GIF;

    private ReelsMediaPolicy() {
    }

    /**
     * Типы для SQL-запроса. Фото отдельным переключателем не добавляется: тип
     * {@code 0} покрывает и фото, и видео, лишнее отсекается в памяти — иначе пришлось бы
     * тянуть из базы каждое фото канала, чтобы выкинуть его.
     */
    public static String mediaTypesSql() {
        return TYPE_PHOTOVIDEO + ", " + TYPE_AUDIO + ", " + TYPE_GIF;
    }

    /** Годится ли пост в ленту «Клипов» при текущих настройках. */
    public static boolean qualifies(MessageObject message, boolean showPhotos) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        // Служебные посты (приглашения, смены аватара) и рекламу в ленту не тащим.
        if (message.isSponsored() || message.getRealId() <= 0 || message.messageOwner.action != null) {
            return false;
        }
        if (message.isVideo() || message.isRoundVideo() || message.isGif()) {
            return true;
        }
        return showPhotos && message.isPhoto();
    }

    /**
     * Проигрывается ли пост как видео. Фото в ленту попадает, но отдельным плеером
     * не открывается: у него нет ни документа, ни звуковой дорожки.
     */
    public static boolean isPlayable(MessageObject message) {
        return message != null && (message.isVideo() || message.isRoundVideo() || message.isGif());
    }
}
