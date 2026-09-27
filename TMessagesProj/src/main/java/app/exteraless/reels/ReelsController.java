package app.exteraless.reels;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Центральный класс «Клипов»: держит окно роликов, ходит за ними через
 * {@link ReelsTimelineLoader}, отмечает просмотренные посты и рассылает изменения через
 * {@link NotificationCenter}. Живёт по одному экземпляру на аккаунт и переживает смену
 * настроек, сверяясь с поколением {@link ReelsConfig}.
 */
public class ReelsController implements NotificationCenter.NotificationCenterDelegate {

    private static final int FULL_CHUNK_ROW_COUNT = 30;
    private static final int MAX_BACKFILL_ROUNDS = 3;

    private static final int INACTIVE_CACHE_CAP_LOW = 200;
    private static final int INACTIVE_CACHE_CAP_AVERAGE = 400;
    private static final int INACTIVE_CACHE_CAP_HIGH = 600;

    private static final long CLOSED_REFRESH_DELAY = 1000L;

    private static final ReelsController[] Instance = new ReelsController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int a = 0; a < lockObjects.length; a++) {
            lockObjects[a] = new Object();
        }
    }

    public final int currentAccount;

    private final ReelsStore store = new ReelsStore();
    private final ReelsSeenTracker seenTracker;
    private final ReelsTimelineLoader loader;
    private final ReelsBackfillCoordinator backfill;

    private final Runnable closedRefreshRunnable = this::runClosedRefresh;
    private boolean closedRefreshScheduled;

    private int backfillRounds;
    private int configGeneration;
    private int sessionGeneration;
    private int uiActiveClients;
    private int resumedUiClients;

    private boolean hasChannels;
    private boolean loading;
    private boolean loadingNewer;
    private boolean olderPagingBoundsDirty;
    private boolean newerPagingBoundsDirty;

    private ReelsController(int account) {
        currentAccount = account;
        seenTracker = new ReelsSeenTracker(account, store.getMessages());
        loader = ReelsTimelineLoader.create(account);
        backfill = new ReelsBackfillCoordinator(account, this::onBackfillRoundFinished);
        AndroidUtilities.runOnUIThread(() -> subscribe(account));
    }

    public static ReelsController getInstance(int account) {
        ReelsController localInstance = Instance[account];
        if (localInstance != null) {
            return localInstance;
        }
        synchronized (lockObjects[account]) {
            localInstance = Instance[account];
            if (localInstance == null) {
                localInstance = new ReelsController(account);
                Instance[account] = localInstance;
            }
        }
        return localInstance;
    }

    public static ReelsController peekInstance(int account) {
        return Instance[account];
    }

    /**
     * Канал годится для «Клипов», если это именно канал (не супергруппа и не сообщество)
     * и пользователь из него не вышел.
     */
    public static boolean isEligibleChannel(TLRPC.Chat chat) {
        return chat != null
                && ChatObject.isChannelAndNotMegaGroup(chat)
                && !ChatObject.isCommunity(chat)
                && !ChatObject.isNotInChat(chat);
    }

    private void subscribe(int account) {
        NotificationCenter notificationCenter = NotificationCenter.getInstance(account);
        notificationCenter.addObserver(this, NotificationCenter.messagesDidLoad);
        notificationCenter.addObserver(this, NotificationCenter.loadingMessagesFailed);
        notificationCenter.addObserver(this, NotificationCenter.messagesDeleted);
        notificationCenter.addObserver(this, NotificationCenter.historyCleared);
        notificationCenter.addObserver(this, NotificationCenter.didReceiveNewMessages);
    }

    private static int getInactiveCacheCap() {
        int performanceClass = SharedConfig.getDevicePerformanceClass();
        if (performanceClass == SharedConfig.PERFORMANCE_CLASS_LOW) {
            return INACTIVE_CACHE_CAP_LOW;
        }
        if (performanceClass == SharedConfig.PERFORMANCE_CLASS_HIGH) {
            return INACTIVE_CACHE_CAP_HIGH;
        }
        return INACTIVE_CACHE_CAP_AVERAGE;
    }

    private boolean isUiActive() {
        return uiActiveClients > 0;
    }

    private ArrayList<MessageObject> createMessageObjects(ArrayList<TLRPC.Message> messages,
                                                         ArrayList<TLRPC.User> users,
                                                         ArrayList<TLRPC.Chat> chats) {
        HashMap<Long, TLRPC.User> usersMap = new HashMap<>(users.size());
        HashMap<Long, TLRPC.Chat> chatsMap = new HashMap<>(chats.size());
        for (int i = 0; i < users.size(); i++) {
            usersMap.put(users.get(i).id, users.get(i));
        }
        for (int i = 0; i < chats.size(); i++) {
            chatsMap.put(chats.get(i).id, chats.get(i));
        }
        ArrayList<MessageObject> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            result.add(new MessageObject(currentAccount, messages.get(i), null, usersMap, chatsMap,
                    null, null, true, true, 0L));
        }
        return result;
    }

    private void ensureCurrentConfig() {
        if (configGeneration != ReelsConfig.getInstance(currentAccount).getGeneration()) {
            applyConfigChange(this::postNeedReload);
        }
    }

    private void postNeedReload(Boolean truncated) {
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.reelsNeedReload, truncated);
    }

    /**
     * Сообщает подписчикам об изменении окна. toStart=true — ролики вставлены в начало
     * (пришли новые посты), иначе список нужно перечитать целиком.
     */
    private void postDataUpdated(ArrayList<MessageObject> added, boolean toStart) {
        NotificationCenter.getInstance(currentAccount)
                .postNotificationName(NotificationCenter.reelsDataUpdated, added, toStart);
    }

    private void onBackfillRoundFinished() {
        if (loading) {
            runAttempt();
        }
    }

    /** Перечитывает состав каналов и сообщает, пришлось ли выкинуть загруженное окно. */
    public void applyConfigChange(Utilities.Callback<Boolean> callback) {
        configGeneration = ReelsConfig.getInstance(currentAccount).getGeneration();
        loader.invalidateChannelCache();
        if (store.isEmpty()) {
            loadChannels((channels, includedCount) -> {
                if (callback != null) {
                    callback.run(Boolean.FALSE);
                }
            });
            return;
        }
        final int generation = sessionGeneration;
        final HashSet<Long> loadedDialogIds = store.getLoadedDialogIds();
        final ReelsTimelineLoader.Cursor newest = new ReelsTimelineLoader.Cursor();
        newest.copyFrom(store.getNewestCursor());
        final ReelsTimelineLoader.Cursor oldest = new ReelsTimelineLoader.Cursor();
        oldest.copyFrom(store.getOldestCursor());
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            ReelsTimelineLoader.ChannelEnumeration enumeration =
                    loader.enumerateChannels(ReelsConfig.getInstance(currentAccount), generation, true);
            ArrayList<Long> freshDialogIds = new ArrayList<>();
            for (int i = 0; i < enumeration.included.size(); i++) {
                long dialogId = enumeration.included.get(i).dialogId;
                if (!loadedDialogIds.contains(dialogId)) {
                    freshDialogIds.add(dialogId);
                }
            }
            ReelsTimelineLoader.WindowPage page = freshDialogIds.isEmpty()
                    ? null
                    : loader.loadChannelWindow(freshDialogIds, newest, oldest);
            ArrayList<MessageObject> messageObjects = page != null
                    ? createMessageObjects(page.messages, page.users, page.chats)
                    : null;
            AndroidUtilities.runOnUIThread(() ->
                    applyReconciledChannelSet(generation, callback, enumeration, page, messageObjects));
        });
    }

    private void applyReconciledChannelSet(int generation, Utilities.Callback<Boolean> callback,
                                           ReelsTimelineLoader.ChannelEnumeration enumeration,
                                           ReelsTimelineLoader.WindowPage page,
                                           ArrayList<MessageObject> messageObjects) {
        if (generation != sessionGeneration) {
            if (callback != null) {
                callback.run(Boolean.FALSE);
            }
            return;
        }
        applyEnumeration(enumeration);
        HashSet<Long> includedDialogIds = new HashSet<>();
        for (int i = 0; i < enumeration.included.size(); i++) {
            includedDialogIds.add(enumeration.included.get(i).dialogId);
        }
        // Каналы, которые пользователь снял, должны исчезнуть сразу, а не после
        // перезагрузки окна: иначе ролики продолжали бы листаться.
        ArrayList<MessageObject> removed = store.removeDialogsExcept(includedDialogIds);
        if (!removed.isEmpty()) {
            postDataUpdated(removed, false);
        }
        boolean truncated = page != null && page.truncated;
        if (page != null && !truncated && messageObjects != null && !messageObjects.isEmpty()) {
            MessagesController messagesController = MessagesController.getInstance(currentAccount);
            messagesController.putUsers(page.users, true);
            messagesController.putChats(page.chats, true);
            store.merge(messageObjects, false);
            postDataUpdated(new ArrayList<>(), false);
        }
        if (truncated) {
            // Окно выросло сверх лимита: проще пересобрать ленту с начала, чем угадывать,
            // какие ролики уже показаны.
            store.clear();
        } else if (loading) {
            olderPagingBoundsDirty = true;
        }
        if (loadingNewer) {
            newerPagingBoundsDirty = true;
        }
        if (callback != null) {
            callback.run(truncated);
        }
    }

    private void applyEnumeration(ReelsTimelineLoader.ChannelEnumeration enumeration) {
        hasChannels = enumeration.hasChannels;
        for (int a = 0, N = enumeration.included.size(); a < N; a++) {
            ReelsTimelineLoader.ChannelSnapshot snapshot = enumeration.included.get(a);
            int readInboxMax = snapshot.readInboxMax;
            if (readInboxMax <= 0 && snapshot.unreadCount <= 0) {
                readInboxMax = snapshot.topMessage;
            }
            seenTracker.applyReadInboxMax(snapshot.dialogId, readInboxMax);
        }
    }

    private void runAttempt() {
        final int generation = sessionGeneration;
        final boolean firstPage = store.getOldestCursor().isEmpty();
        final ReelsTimelineLoader.Cursor oldest = new ReelsTimelineLoader.Cursor();
        oldest.copyFrom(store.getOldestCursor());
        final HashSet<Long> exhausted = backfill.getExhaustedSnapshot();
        final ReelsConfig config = ReelsConfig.getInstance(currentAccount);
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            ReelsTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(config, generation, false);
            if (enumeration.included.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> applyEmptyPage(generation, enumeration));
                return;
            }
            ReelsTimelineLoader.OlderPage page = loader.loadOlderPage(enumeration.included, oldest, exhausted);
            ArrayList<MessageObject> messageObjects = createMessageObjects(page.messages, page.users, page.chats);
            AndroidUtilities.runOnUIThread(() -> applyOlderPage(generation, enumeration, page, firstPage, messageObjects));
        });
    }

    private void applyEmptyPage(int generation, ReelsTimelineLoader.ChannelEnumeration enumeration) {
        if (generation != sessionGeneration) {
            return;
        }
        applyEnumeration(enumeration);
        olderPagingBoundsDirty = false;
        seenTracker.clear();
        loading = false;
        store.setEndReached(true);
        postDataUpdated(new ArrayList<>(), false);
    }

    private void applyOlderPage(int generation, ReelsTimelineLoader.ChannelEnumeration enumeration,
                                ReelsTimelineLoader.OlderPage page, boolean firstPage,
                                ArrayList<MessageObject> messageObjects) {
        if (generation != sessionGeneration) {
            return;
        }
        if (olderPagingBoundsDirty) {
            olderPagingBoundsDirty = false;
            backfillRounds = 0;
            runAttempt();
            return;
        }
        applyEnumeration(enumeration);
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        store.setOldestCursor(page.last.date, page.last.uid, page.last.mid);
        if (firstPage && !page.first.isEmpty()) {
            store.setNewestCursor(page.first.date, page.first.uid, page.first.mid);
        }
        messagesController.putUsers(page.users, true);
        messagesController.putChats(page.chats, true);

        ArrayList<MessageObject> filtered = filterQualifying(messageObjects);
        ArrayList<MessageObject> appended = store.merge(filtered, false);
        if (appended.isEmpty() && page.lastChunkRowCount == FULL_CHUNK_ROW_COUNT) {
            // Страница была полной, но в ленту не попало ни одного ролика: значит, дальше
            // есть что искать, просто смотрим следующий курсор.
            runAttempt();
            return;
        }
        boolean endReached = !page.hasIncomplete && page.lastChunkRowCount < FULL_CHUNK_ROW_COUNT;
        if (appended.isEmpty() && !endReached && !page.backfillCandidates.isEmpty()
                && backfillRounds < MAX_BACKFILL_ROUNDS) {
            backfillRounds++;
            backfill.startRound(page.backfillCandidates);
            return;
        }
        loading = false;
        store.setEndReached(endReached);
        postDataUpdated(appended, false);
    }

    /**
     * Отсеивает посты, которые не показываем (фото при выключенном тумблере, голосовые,
     * сервисные сообщения). Сама выборка уже ограничена нужными типами медиа, но тип
     * «аудио» в канале — это ещё и голосовые, а служебные посты в базе живут рядом.
     */
    private ArrayList<MessageObject> filterQualifying(ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        boolean showPhotos = ReelsConfig.getInstance(currentAccount).getShowPhotos();
        ArrayList<MessageObject> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (ReelsMediaPolicy.qualifies(message, showPhotos)) {
                result.add(message);
            }
        }
        return result;
    }

    private void runLoadNewer() {
        final int generation = sessionGeneration;
        final ReelsTimelineLoader.Cursor newest = new ReelsTimelineLoader.Cursor();
        newest.copyFrom(store.getNewestCursor());
        final ReelsConfig config = ReelsConfig.getInstance(currentAccount);
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            ReelsTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(config, generation, false);
            if (enumeration.included.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> applyEmptyNewerPage(generation));
                return;
            }
            ReelsTimelineLoader.NewerPage page = loader.loadNewerPage(enumeration.included, newest);
            ArrayList<MessageObject> messageObjects = createMessageObjects(page.messages, page.users, page.chats);
            AndroidUtilities.runOnUIThread(() -> applyNewerPage(generation, enumeration, page, messageObjects));
        });
    }

    private void applyEmptyNewerPage(int generation) {
        if (generation != sessionGeneration) {
            return;
        }
        newerPagingBoundsDirty = false;
        loadingNewer = false;
    }

    private void applyNewerPage(int generation, ReelsTimelineLoader.ChannelEnumeration enumeration,
                                ReelsTimelineLoader.NewerPage page, ArrayList<MessageObject> messageObjects) {
        if (generation != sessionGeneration) {
            return;
        }
        if (newerPagingBoundsDirty) {
            newerPagingBoundsDirty = false;
            if (!store.getNewestCursor().isEmpty()) {
                runLoadNewer();
                return;
            }
            loadingNewer = false;
            return;
        }
        loadingNewer = false;
        applyEnumeration(enumeration);
        store.setNewestCursor(page.first.date, page.first.uid, page.first.mid);
        if (page.messages.isEmpty()) {
            return;
        }
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        messagesController.putUsers(page.users, true);
        messagesController.putChats(page.chats, true);
        // Вставка сверху сдвигает индексы, поэтому UI пересчитает позицию сам.
        store.merge(filterQualifying(messageObjects), true);
        postDataUpdated(new ArrayList<>(), true);
    }

    private void runClosedRefresh() {
        closedRefreshScheduled = false;
        if (isUiActive() || loadingNewer || store.isEmpty() || store.getNewestCursor().isEmpty()) {
            return;
        }
        loadNewer();
    }

    private void scheduleClosedRefresh() {
        if (closedRefreshScheduled) {
            return;
        }
        closedRefreshScheduled = true;
        AndroidUtilities.runOnUIThread(closedRefreshRunnable, CLOSED_REFRESH_DELAY);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDidLoad) {
            backfill.onMessagesDidLoad(args);
        } else if (id == NotificationCenter.loadingMessagesFailed) {
            backfill.onLoadingMessagesFailed(args);
        } else if (id == NotificationCenter.messagesDeleted) {
            if (isUiActive() || (Boolean) args[2]) {
                return;
            }
            long dialogId = (Long) args[1];
            if (dialogId == 0) {
                return;
            }
            if (dialogId > 0) {
                dialogId = -dialogId;
            }
            store.deleteMessages(dialogId, (ArrayList<Integer>) args[0], new boolean[1]);
            postDataUpdated(new ArrayList<>(), false);
        } else if (id == NotificationCenter.historyCleared) {
            if (isUiActive()) {
                return;
            }
            long dialogId = (Long) args[0];
            if (DialogObject.isChatDialog(dialogId)) {
                store.deleteHistory(dialogId, (Integer) args[1], new boolean[1]);
                postDataUpdated(new ArrayList<>(), false);
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            if (isUiActive() || (Boolean) args[2] || store.isEmpty() || store.getNewestCursor().isEmpty()
                    || !isIncludedChannelPost((Long) args[0])) {
                return;
            }
            scheduleClosedRefresh();
        }
    }

    /** Канал выбран пользователем и годится для ленты. */
    public boolean isIncludedChannelPost(long dialogId) {
        if (!DialogObject.isChatDialog(dialogId)) {
            return false;
        }
        if (!ReelsConfig.getInstance(currentAccount).isIncluded(dialogId)) {
            return false;
        }
        return isEligibleChannel(MessagesController.getInstance(currentAccount).getChat(-dialogId));
    }

    /** Состав источников: экран настроек показывает все каналы аккаунта. */
    public void loadChannels(ChannelsCallback callback) {
        final ReelsConfig config = ReelsConfig.getInstance(currentAccount);
        final int generation = sessionGeneration;
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            ReelsTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(config, generation, false);
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != sessionGeneration) {
                    return;
                }
                hasChannels = enumeration.hasChannels;
                MessagesController.getInstance(currentAccount).putChats(enumeration.channels, true);
                if (callback != null) {
                    callback.onChannels(enumeration.channels, enumeration.included.size());
                }
            });
        });
    }

    /**
     * Отдаёт уже накопленное окно, если оно есть; иначе запускает первую загрузку.
     * Подписчику в любом случае придёт reelsDataUpdated — с готовым списком или с тем,
     * что загрузка только началась.
     */
    public boolean loadInitial() {
        ensureCurrentConfig();
        if (!store.isEmpty() && !store.getOldestCursor().isEmpty()) {
            postDataUpdated(new ArrayList<>(), false);
            return true;
        }
        if (loadOlder()) {
            return true;
        }
        if (!store.isEmpty()) {
            // Доливать нечего (конец ленты), но показать уже загруженное надо.
            postDataUpdated(new ArrayList<>(), false);
            return true;
        }
        return false;
    }

    public boolean loadOlder() {
        ensureCurrentConfig();
        if (loading || (store.isEndReached() && !store.getOldestCursor().isEmpty())) {
            return false;
        }
        loading = true;
        backfillRounds = 0;
        runAttempt();
        return true;
    }

    public boolean loadNewer() {
        ensureCurrentConfig();
        if (loadingNewer || store.getNewestCursor().isEmpty()) {
            return false;
        }
        loadingNewer = true;
        runLoadNewer();
        return true;
    }

    /** Ролик долистали до конца: пора отмечать просмотренным. */
    public void onPageSeen(MessageObject message) {
        if (message == null || !ReelsConfig.getInstance(currentAccount).getMarkSeen()) {
            return;
        }
        seenTracker.onPageSeen(message.getDialogId(), message.getRealId());
    }

    public void cancelLoads() {
        sessionGeneration++;
        loading = false;
        loadingNewer = false;
        olderPagingBoundsDirty = false;
        newerPagingBoundsDirty = false;
        backfillRounds = 0;
        backfill.cancel();
    }

    public void clear() {
        sessionGeneration++;
        configGeneration = ReelsConfig.getInstance(currentAccount).getGeneration();
        seenTracker.clear();
        store.clear();
        loading = false;
        loadingNewer = false;
        olderPagingBoundsDirty = false;
        newerPagingBoundsDirty = false;
        backfill.cancel();
        backfill.clearExhausted();
        if (closedRefreshScheduled) {
            AndroidUtilities.cancelRunOnUIThread(closedRefreshRunnable);
            closedRefreshScheduled = false;
        }
    }

    public ArrayList<MessageObject> getMessages() {
        return store.getMessages();
    }

    public MessageObject getMessage(long dialogId, int messageId) {
        return store.find(dialogId, messageId);
    }

    public boolean hasChannels() {
        return hasChannels;
    }

    public boolean isEndReached() {
        return store.isEndReached();
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean isLoadingNewer() {
        return loadingNewer;
    }

    /**
     * Считает открытые экраны «Клипов»: на первом включает загрузку, на последнем
     * гасит запросы и подрезает окно.
     */
    public void setUiActive(boolean active) {
        if (!active) {
            if (uiActiveClients == 0) {
                return;
            }
            uiActiveClients--;
            if (uiActiveClients == 0) {
                cancelLoads();
                trimForInactiveCache();
            }
            return;
        }
        uiActiveClients++;
        if (uiActiveClients > 1) {
            return;
        }
        if (closedRefreshScheduled) {
            AndroidUtilities.cancelRunOnUIThread(closedRefreshRunnable);
            closedRefreshScheduled = false;
        }
        if (loadingNewer) {
            cancelLoads();
        }
    }

    public void setUiResumed(boolean resumed) {
        if (resumed) {
            resumedUiClients++;
        } else if (resumedUiClients > 0) {
            resumedUiClients--;
        }
    }

    public void trimForInactiveCache() {
        if (isUiActive() || store.isEmpty()) {
            return;
        }
        store.trim(getInactiveCacheCap());
    }

    public interface ChannelsCallback {
        void onChannels(ArrayList<TLRPC.Chat> channels, int includedCount);
    }
}
