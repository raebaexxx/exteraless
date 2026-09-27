package app.exteraless.reels;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Настройки «Клипов» для одного аккаунта: какие каналы попадают в ленту, показывать ли
 * фотографии, стартовать ли без звука и отмечать ли просмотренные посты.
 *
 * <p>Отличие от {@code FeedConfig}: каналы заданы белым списком, а не исключениями.
 * Пустой список означает «показывать нечего» — иначе фича включилась бы сразу у всех
 * и притащила в ленту всё подряд, что пользователю не нужно.
 *
 * <p>Хранится в отдельном SharedPreferences-файле на аккаунт; счётчик поколений растёт
 * при любом изменении и служит сигналом пересобрать выборку постов.
 */
public final class ReelsConfig {

    private static final String PREFERENCES_PREFIX = "reelsconfig";
    private static final String KEY_INCLUDED_CHANNELS = "includedChannels";
    private static final String KEY_INCLUDE_ARCHIVED = "includeArchived";
    private static final String KEY_SHOW_PHOTOS = "showPhotos";
    private static final String KEY_AUTOPLAY = "autoplay";
    private static final String KEY_START_MUTED = "startMuted";
    private static final String KEY_MARK_SEEN = "markSeen";
    private static final String KEY_LAST_DIALOG_ID = "lastDialogId";
    private static final String KEY_LAST_MESSAGE_ID = "lastMessageId";

    private static final ReelsConfig[] instances = new ReelsConfig[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int account = 0; account < lockObjects.length; account++) {
            lockObjects[account] = new Object();
        }
    }

    private final SharedPreferences preferences;
    private final AtomicInteger generation = new AtomicInteger();

    private volatile Set<Long> includedChannels;
    private volatile boolean includeArchived;
    private volatile boolean showPhotos;
    private volatile boolean autoplay;
    private volatile boolean startMuted;
    private volatile boolean markSeen;

    private ReelsConfig(int account) {
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFERENCES_PREFIX + account, 0);
        includedChannels = readIncluded();
        includeArchived = preferences.getBoolean(KEY_INCLUDE_ARCHIVED, false);
        showPhotos = preferences.getBoolean(KEY_SHOW_PHOTOS, false);
        autoplay = preferences.getBoolean(KEY_AUTOPLAY, true);
        startMuted = preferences.getBoolean(KEY_START_MUTED, true);
        markSeen = preferences.getBoolean(KEY_MARK_SEEN, true);
    }

    public static ReelsConfig getInstance(int num) {
        ReelsConfig cached = instances[num];
        if (cached != null) {
            return cached;
        }
        synchronized (lockObjects[num]) {
            cached = instances[num];
            if (cached == null) {
                cached = new ReelsConfig(num);
                instances[num] = cached;
            }
        }
        return cached;
    }

    private Set<Long> readIncluded() {
        Set<String> stored = preferences.getStringSet(KEY_INCLUDED_CHANNELS, null);
        if (stored == null) {
            return Collections.emptySet();
        }
        HashSet<Long> parsed = new HashSet<>();
        for (String value : stored) {
            try {
                parsed.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return Collections.unmodifiableSet(parsed);
    }

    private void applyIncluded(Set<Long> updated) {
        includedChannels = Collections.unmodifiableSet(updated);
        generation.incrementAndGet();
        HashSet<String> stored = new HashSet<>();
        for (Long dialogId : updated) {
            stored.add(String.valueOf(dialogId.longValue()));
        }
        preferences.edit().putStringSet(KEY_INCLUDED_CHANNELS, stored).apply();
    }

    /**
     * Неизменяемый снимок каналов-источников: читать можно с любого потока, выборка
     * постов работает именно с ним, а не с живой коллекцией.
     */
    public Set<Long> getIncludedSnapshot() {
        return includedChannels;
    }

    public boolean isIncluded(long dialogId) {
        return includedChannels.contains(dialogId);
    }

    public boolean hasIncludedChannels() {
        return !includedChannels.isEmpty();
    }

    public void setIncluded(long dialogId, boolean included) {
        HashSet<Long> updated = new HashSet<>(includedChannels);
        boolean changed = included ? updated.add(dialogId) : updated.remove(dialogId);
        if (changed) {
            applyIncluded(updated);
        }
    }

    /**
     * Заменяет список источников целиком. Один экран с «выбрать все» не должен делать
     * сотню отдельных записей в настройки и сто поколений пересборки.
     */
    public void setIncludedChannels(Collection<Long> dialogIds) {
        HashSet<Long> updated = new HashSet<>(dialogIds);
        if (updated.equals(includedChannels)) {
            return;
        }
        applyIncluded(updated);
    }

    public void removeIncluded(Collection<Long> dialogIds) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return;
        }
        HashSet<Long> updated = new HashSet<>(includedChannels);
        if (updated.removeAll(dialogIds)) {
            applyIncluded(updated);
        }
    }

    public boolean getIncludeArchived() {
        return includeArchived;
    }

    public void setIncludeArchived(boolean value) {
        if (includeArchived == value) {
            return;
        }
        includeArchived = value;
        generation.incrementAndGet();
        preferences.edit().putBoolean(KEY_INCLUDE_ARCHIVED, value).apply();
    }

    public boolean getShowPhotos() {
        return showPhotos;
    }

    public void setShowPhotos(boolean value) {
        if (showPhotos == value) {
            return;
        }
        showPhotos = value;
        generation.incrementAndGet();
        preferences.edit().putBoolean(KEY_SHOW_PHOTOS, value).apply();
    }

    public boolean getAutoplay() {
        return autoplay;
    }

    public void setAutoplay(boolean value) {
        if (autoplay == value) {
            return;
        }
        autoplay = value;
        preferences.edit().putBoolean(KEY_AUTOPLAY, value).apply();
    }

    /**
     * Стартовать без звука. В отличие от остальных переключателей выбор медиа не меняет,
     * поэтому поколение настроек не трогаем.
     */
    public boolean getStartMuted() {
        return startMuted;
    }

    public void setStartMuted(boolean value) {
        if (startMuted == value) {
            return;
        }
        startMuted = value;
        preferences.edit().putBoolean(KEY_START_MUTED, value).apply();
    }

    public boolean getMarkSeen() {
        return markSeen;
    }

    public void setMarkSeen(boolean value) {
        if (markSeen == value) {
            return;
        }
        markSeen = value;
        preferences.edit().putBoolean(KEY_MARK_SEEN, value).apply();
    }

    /** Где остановились: чтобы вернуться на тот же ролик после перезапуска приложения. */
    public void setLastPosition(long dialogId, int messageId) {
        if (dialogId == 0 || messageId <= 0) {
            return;
        }
        preferences.edit()
                .putLong(KEY_LAST_DIALOG_ID, dialogId)
                .putInt(KEY_LAST_MESSAGE_ID, messageId)
                .apply();
    }

    public long getLastDialogId() {
        return preferences.getLong(KEY_LAST_DIALOG_ID, 0);
    }

    public int getLastMessageId() {
        return preferences.getInt(KEY_LAST_MESSAGE_ID, 0);
    }

    /**
     * Номер поколения настроек: меняется при каждой правке состава ленты.
     * Потребители сравнивают его со своим сохранённым и пересобирают выборку при расхождении.
     */
    public int getGeneration() {
        return generation.get();
    }
}
