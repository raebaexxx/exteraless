package app.exteraless.reels;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Отметка просмотренных роликов.
 *
 * <p>Держит по каждому каналу максимальный просмотренный id и отправляет его на сервер
 * пачкой с задержкой: иначе быстрая прокрутка превратилась бы в поток запросов, а бейдж
 * «Чатов» рос бы от каждого пролистывания.
 */
final class ReelsSeenTracker {

    private static final long FLUSH_DELAY_MS = 1000L;
    private static final int NO_READ_ID = 0;

    private final int currentAccount;
    private final ArrayList<MessageObject> timeline;

    private final LongSparseArray<Integer> readInboxMaxByDialog = new LongSparseArray<>();
    private final LongSparseArray<Integer> pendingMaxReadId = new LongSparseArray<>();

    private final Runnable flushRunnable = this::flush;

    private boolean flushScheduled;

    ReelsSeenTracker(int currentAccount, ArrayList<MessageObject> timeline) {
        this.currentAccount = currentAccount;
        this.timeline = timeline;
    }

    /** Обновляет известный максимум прочитанного, пришедший извне (из диалога или с сервера). */
    void applyReadInboxMax(long dialogId, int maxReadId) {
        if (maxReadId > readInboxMaxByDialog.get(dialogId, NO_READ_ID)) {
            readInboxMaxByDialog.put(dialogId, maxReadId);
        }
    }

    /** Досылает накопленное и сбрасывает состояние — вызывается при перезагрузке ленты. */
    void clear() {
        if (flushScheduled) {
            AndroidUtilities.cancelRunOnUIThread(flushRunnable);
            flushScheduled = false;
        }
        flush();
        readInboxMaxByDialog.clear();
    }

    /** Ролик показан пользователем: запоминаем и планируем отложенную отправку. */
    void onPageSeen(long dialogId, int messageId) {
        if (dialogId == 0 || messageId <= 0 || messageId <= getEffectiveReadInboxMax(dialogId)) {
            return;
        }
        Integer pending = pendingMaxReadId.get(dialogId);
        if (pending != null && pending >= messageId) {
            return;
        }
        pendingMaxReadId.put(dialogId, messageId);
        if (!flushScheduled) {
            flushScheduled = true;
            AndroidUtilities.runOnUIThread(flushRunnable, FLUSH_DELAY_MS);
        }
    }

    private int countTimelineRows(long dialogId, int fromIdExclusive, int toIdInclusive) {
        int count = 0;
        for (int i = 0; i < timeline.size(); i++) {
            MessageObject message = timeline.get(i);
            if (message == null || message.getDialogId() != dialogId) {
                continue;
            }
            int realId = message.getRealId();
            if (realId > fromIdExclusive && realId <= toIdInclusive) {
                count++;
            }
        }
        return count;
    }

    private void flush() {
        flushScheduled = false;
        if (pendingMaxReadId.isEmpty()) {
            return;
        }
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        int currentTime = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        HashSet<Long> touched = new HashSet<>();
        for (int i = 0; i < pendingMaxReadId.size(); i++) {
            long dialogId = pendingMaxReadId.keyAt(i);
            int maxReadId = pendingMaxReadId.valueAt(i);
            int knownMaxReadId = readInboxMaxByDialog.get(dialogId, NO_READ_ID);
            if (maxReadId > knownMaxReadId) {
                readInboxMaxByDialog.put(dialogId, maxReadId);
                int countDiff = Math.max(countTimelineRows(dialogId, knownMaxReadId, maxReadId), 1);
                messagesController.markDialogAsRead(dialogId, maxReadId, 0, currentTime, false, 0L, countDiff, true, 0);
            }
            touched.add(dialogId);
        }
        pendingMaxReadId.clear();
    }

    private int getEffectiveReadInboxMax(long dialogId) {
        return Math.max(readInboxMaxByDialog.get(dialogId, NO_READ_ID), pendingMaxReadId.get(dialogId, NO_READ_ID));
    }
}
