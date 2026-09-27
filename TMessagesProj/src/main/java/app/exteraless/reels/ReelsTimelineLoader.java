package app.exteraless.reels;

import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteException;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Чтение «Клипов» из локальной базы: перечисление каналов-источников и выборка их медиа,
 * слитых в один поток по ключу (date, uid, mid) в порядке убывания.
 *
 * <p>В отличие от ленты каналов выборка идёт не по {@code messages_v2}, а по
 * {@code media_v4} — только там тип медиа известен заранее, и есть индекс
 * {@code uid_type_date_mid_idx_media_v4} под запрос «свежие ролики канала».
 *
 * <p>Все методы выполняются на очереди {@code MessagesStorage}.
 */
final class ReelsTimelineLoader {

    private static final int CHUNK_SIZE = 30;
    private static final int NEWER_PAGE_SIZE = 20;
    private static final int WINDOW_SIZE = 300;
    private static final int DIALOG_BATCH_SIZE = 64;

    private final int currentAccount;

    private ChannelSet channelSetCache;

    private ReelsTimelineLoader(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    static ReelsTimelineLoader create(int currentAccount) {
        return new ReelsTimelineLoader(currentAccount);
    }

    private enum Direction {
        OLDER("<"),
        NEWER(">");

        final String operator;

        Direction(String operator) {
            this.operator = operator;
        }
    }

    static final class ChannelEnumeration {
        boolean hasChannels;
        final ArrayList<ChannelSnapshot> included = new ArrayList<>();
        final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    }

    private static final class ChannelSet {
        final int configGen;
        final int sessionGen;
        final ArrayList<long[]> includedRows = new ArrayList<>();
        final ArrayList<TLRPC.Chat> channels = new ArrayList<>();

        ChannelSet(int sessionGen, int configGen) {
            this.sessionGen = sessionGen;
            this.configGen = configGen;
        }
    }

    static final class ChannelSnapshot {
        int depthDate;
        int depthMid;
        final long dialogId;
        boolean hasCached;
        boolean hasHole;
        int holeEnd;
        boolean localStartReached;
        final int readInboxMax;
        final int topMessage;
        final int unreadCount;

        ChannelSnapshot(long dialogId, int readInboxMax, int unreadCount, int topMessage) {
            this.dialogId = dialogId;
            this.readInboxMax = readInboxMax;
            this.unreadCount = unreadCount;
            this.topMessage = topMessage;
        }
    }

    static final class Cursor {
        int date;
        int mid;
        long uid;

        boolean isEmpty() {
            return date == 0;
        }

        void set(int date, long uid, int mid) {
            this.date = date;
            this.uid = uid;
            this.mid = mid;
        }

        void copyFrom(Cursor other) {
            set(other.date, other.uid, other.mid);
        }
    }

    static final class NewerPage {
        boolean hasMore;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        final Cursor first = new Cursor();
    }

    static final class OlderPage {
        boolean hasIncomplete;
        int lastChunkRowCount;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        final ArrayList<long[]> backfillCandidates = new ArrayList<>();
        final Cursor last = new Cursor();
        final Cursor first = new Cursor();
    }

    static final class WindowPage {
        boolean truncated;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
    }

    private static void appendCursorBound(StringBuilder sql, Cursor cursor, Direction direction, boolean inclusive) {
        String operator = direction.operator;
        String midOperator = inclusive ? operator + "= " : operator + " ";
        sql.append(" AND (date ");
        sql.append(operator);
        sql.append(' ');
        sql.append(cursor.date);
        sql.append(" OR date = ");
        sql.append(cursor.date);
        sql.append(" AND (uid ");
        sql.append(operator);
        sql.append(' ');
        sql.append(cursor.uid);
        sql.append(" OR uid = ");
        sql.append(cursor.uid);
        sql.append(" AND mid ");
        sql.append(midOperator);
        sql.append(cursor.mid);
        sql.append("))");
    }

    private ChannelSet buildChannelSet(ReelsConfig config, int sessionGen, int configGen) {
        ChannelSet channelSet = new ChannelSet(sessionGen, configGen);
        try {
            MessagesStorage messagesStorage = MessagesStorage.getInstance(currentAccount);
            StringBuilder sql = new StringBuilder("SELECT did, inbox_max, unread_count, last_mid FROM dialogs WHERE did < 0");
            if (!config.getIncludeArchived()) {
                sql.append(" AND folder_id != 1");
            }
            sql.append(" ORDER BY date DESC");

            ArrayList<long[]> rows = new ArrayList<>();
            ArrayList<Long> chatIds = new ArrayList<>();
            SQLiteCursor sqlCursor = messagesStorage.getDatabase().queryFinalized(sql.toString(), new Object[0]);
            try {
                while (sqlCursor.next()) {
                    long dialogId = sqlCursor.longValue(0);
                    int readInboxMax = sqlCursor.intValue(1);
                    int unreadCount = sqlCursor.intValue(2);
                    int topMessage = sqlCursor.intValue(3);
                    // Каналы вне белого списка в выборку не идут, но сам список диалогов
                    // нужен экрану настроек — поэтому сначала читаем всё, фильтруем ниже.
                    chatIds.add(-dialogId);
                    rows.add(new long[]{dialogId, readInboxMax, unreadCount, topMessage});
                }
            } finally {
                sqlCursor.dispose();
            }
            if (chatIds.isEmpty()) {
                return channelSet;
            }

            ArrayList<TLRPC.Chat> chats = new ArrayList<>();
            messagesStorage.getChatsInternal(TextUtils.join(",", chatIds), chats);
            LongSparseArray<TLRPC.Chat> chatsById = new LongSparseArray<>(chats.size());
            for (int i = 0; i < chats.size(); i++) {
                TLRPC.Chat chat = chats.get(i);
                if (chat != null) {
                    chatsById.put(chat.id, chat);
                }
            }
            for (int i = 0; i < rows.size(); i++) {
                long[] row = rows.get(i);
                TLRPC.Chat chat = chatsById.get(-row[0]);
                if (!ReelsController.isEligibleChannel(chat)) {
                    continue;
                }
                channelSet.channels.add(chat);
                if (config.isIncluded(row[0])) {
                    channelSet.includedRows.add(row);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return channelSet;
    }

    /**
     * Состав источников: все подходящие каналы аккаунта (для экрана настроек) и снимки тех,
     * что выбраны пользователем. Кэшируется до смены поколения сессии или настроек.
     */
    ChannelEnumeration enumerateChannels(ReelsConfig config, int sessionGen, boolean forceRefresh) {
        ChannelSet channelSet = channelSetCache;
        int configGen = config.getGeneration();
        if (forceRefresh || channelSet == null || channelSet.sessionGen != sessionGen || channelSet.configGen != configGen) {
            channelSet = buildChannelSet(config, sessionGen, configGen);
            channelSetCache = channelSet;
        }
        ChannelEnumeration enumeration = new ChannelEnumeration();
        enumeration.channels.addAll(channelSet.channels);
        for (int i = 0; i < channelSet.includedRows.size(); i++) {
            long[] row = channelSet.includedRows.get(i);
            enumeration.included.add(new ChannelSnapshot(row[0], (int) row[1], (int) row[2], (int) row[3]));
        }
        enumeration.hasChannels = !enumeration.included.isEmpty();
        return enumeration;
    }

    void invalidateChannelCache() {
        channelSetCache = null;
    }

    private TLRPC.Message readMessage(SQLiteCursor sqlCursor) throws SQLiteException {
        NativeByteBuffer data = sqlCursor.byteBufferValue(0);
        if (data == null) {
            return null;
        }
        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
        if (message == null) {
            data.reuse();
            return null;
        }
        message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId);
        data.reuse();
        if (message instanceof TLRPC.TL_messageEmpty || message.action != null) {
            return null;
        }
        message.id = sqlCursor.intValue(1);
        message.date = sqlCursor.intValue(2);
        message.dialog_id = sqlCursor.longValue(3);
        return message;
    }

    private int loadChunk(MessagesStorage messagesStorage, String dialogIds, int minDate, OlderPage page,
                          ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad) throws SQLiteException {
        StringBuilder sql = new StringBuilder("SELECT data, mid, date, uid FROM media_v4 WHERE uid IN (");
        sql.append(dialogIds);
        sql.append(") AND type IN (");
        sql.append(ReelsMediaPolicy.mediaTypesSql());
        sql.append(") AND mid > 0");
        if (minDate > 0) {
            sql.append(" AND date >= ");
            sql.append(minDate);
        }
        if (!page.last.isEmpty()) {
            appendCursorBound(sql, page.last, Direction.OLDER, false);
        }
        sql.append(" ORDER BY date DESC, uid DESC, mid DESC LIMIT ");
        sql.append(CHUNK_SIZE);

        int rowCount = 0;
        SQLiteCursor sqlCursor = messagesStorage.getDatabase().queryFinalized(sql.toString(), new Object[0]);
        try {
            while (sqlCursor.next()) {
                rowCount++;
                page.last.set(sqlCursor.intValue(2), sqlCursor.longValue(3), sqlCursor.intValue(1));
                if (page.first.isEmpty()) {
                    page.first.set(page.last.date, page.last.uid, page.last.mid);
                }
                TLRPC.Message message = readMessage(sqlCursor);
                if (message != null) {
                    page.messages.add(message);
                    MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                }
            }
        } finally {
            sqlCursor.dispose();
        }
        return rowCount;
    }

    /**
     * Выясняет, докуда у каждого канала есть локальное медиа. Дыры в {@code media_holes_v2}
     * означают, что в середине истории медиа не хватает: такой канал считается неполным, и его
     * глубина поднимает нижнюю границу выборки, чтобы в ленте не было видно дыры в датах.
     * Пустые каналы (медиа нет вообще) тоже неполные: для них страница выходит пустой
     * и летит запрос на сервер.
     */
    private void loadChannelDepths(MessagesStorage messagesStorage, ArrayList<ChannelSnapshot> channels,
                                   String dialogIdsSql) throws SQLiteException {
        HashMap<Long, Integer> holeEnds = new HashMap<>();
        SQLiteCursor holesCursor = messagesStorage.getDatabase().queryFinalized(
                "SELECT uid, max(end) FROM media_holes_v2 WHERE uid IN (" + dialogIdsSql
                        + ") AND type IN (" + ReelsMediaPolicy.mediaTypesSql() + ") GROUP BY uid", new Object[0]);
        try {
            while (holesCursor.next()) {
                holeEnds.put(holesCursor.longValue(0), holesCursor.intValue(1));
            }
        } finally {
            holesCursor.dispose();
        }

        LongSparseArray<ChannelSnapshot> byDialogId = new LongSparseArray<>(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            ChannelSnapshot channel = channels.get(i);
            channel.depthMid = 0;
            channel.depthDate = Integer.MAX_VALUE;
            channel.hasCached = false;
            channel.hasHole = holeEnds.containsKey(channel.dialogId);
            channel.holeEnd = channel.hasHole ? holeEnds.get(channel.dialogId) : 0;
            channel.localStartReached = false;
            byDialogId.put(channel.dialogId, channel);
        }
        final String types = ReelsMediaPolicy.mediaTypesSql();
        int from = 0;
        while (from < channels.size()) {
            int to = Math.min(from + DIALOG_BATCH_SIZE, channels.size());
            StringBuilder sql = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (sql.length() > 0) {
                    sql.append(" UNION ALL ");
                }
                ChannelSnapshot channel = channels.get(i);
                sql.append("SELECT uid, mid, date FROM (SELECT uid, mid, date FROM media_v4 WHERE uid = ");
                sql.append(channel.dialogId);
                sql.append(" AND type IN (");
                sql.append(types);
                sql.append(") AND mid >= ");
                sql.append(Math.max(channel.holeEnd, 1));
                sql.append(" ORDER BY date ASC, mid ASC LIMIT 1)");
            }
            SQLiteCursor sqlCursor = messagesStorage.getDatabase().queryFinalized(sql.toString(), new Object[0]);
            try {
                while (sqlCursor.next()) {
                    ChannelSnapshot channel = byDialogId.get(sqlCursor.longValue(0));
                    if (channel != null) {
                        channel.depthMid = sqlCursor.intValue(1);
                        channel.depthDate = sqlCursor.intValue(2);
                        channel.hasCached = true;
                    }
                }
            } finally {
                sqlCursor.dispose();
            }
            from = to;
        }
        for (int i = 0; i < channels.size(); i++) {
            ChannelSnapshot channel = channels.get(i);
            channel.localStartReached = !channel.hasHole && channel.hasCached;
        }
    }

    /**
     * Основная выборка вниз от курсора. Если хотя бы одному выбранному каналу нечего
     * показать вовсе, страница возвращается пустой, а кандидаты на дозагрузку — в
     * {@code backfillCandidates}: сначала качаем с сервера, потом переспрашиваем базу.
     */
    OlderPage loadOlderPage(ArrayList<ChannelSnapshot> channels, Cursor from, HashSet<Long> completeDialogIds) {
        OlderPage page = new OlderPage();
        page.last.set(from.date, from.uid, from.mid);
        try {
            ArrayList<Long> dialogIds = new ArrayList<>(channels.size());
            for (int i = 0; i < channels.size(); i++) {
                dialogIds.add(channels.get(i).dialogId);
            }
            String dialogIdsSql = TextUtils.join(",", dialogIds);
            MessagesStorage messagesStorage = MessagesStorage.getInstance(currentAccount);

            loadChannelDepths(messagesStorage, channels, dialogIdsSql);

            int minDate = 0;
            for (int i = 0; i < channels.size(); i++) {
                ChannelSnapshot channel = channels.get(i);
                boolean complete = completeDialogIds.contains(channel.dialogId);
                if (channel.localStartReached || complete) {
                    continue;
                }
                page.hasIncomplete = true;
                minDate = Math.max(minDate, channel.depthDate);
                // Просить историю надо с самой верхней точки, где мы уже уверены, что
                // локальные данные непрерывны: с конца дыры, иначе сервер вернёт то же самое.
                int knownTop = Math.max(channel.holeEnd, channel.topMessage);
                int backfillFromMessageId = channel.hasCached
                        ? channel.depthMid
                        : knownTop > 0 ? knownTop + 1 : 0;
                page.backfillCandidates.add(new long[]{channel.dialogId, backfillFromMessageId, channel.depthDate});
            }
            if (minDate == Integer.MAX_VALUE) {
                return page;
            }
            Collections.sort(page.backfillCandidates, new Comparator<long[]>() {
                @Override
                public int compare(long[] first, long[] second) {
                    return Long.compare(second[2], first[2]);
                }
            });

            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            do {
                int chunkRows = loadChunk(messagesStorage, dialogIdsSql, minDate, page, usersToLoad, chatsToLoad);
                page.lastChunkRowCount = chunkRows;
                if (chunkRows < CHUNK_SIZE) {
                    break;
                }
            } while (!page.last.isEmpty());

            for (int i = 0; i < dialogIds.size(); i++) {
                long chatId = -dialogIds.get(i);
                if (!chatsToLoad.contains(chatId)) {
                    chatsToLoad.add(chatId);
                }
            }
            if (!usersToLoad.isEmpty()) {
                messagesStorage.getUsersInternal(usersToLoad, page.users);
            }
            if (!chatsToLoad.isEmpty()) {
                messagesStorage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return page;
    }

    /**
     * Догружает ролики новее переданного курсора — то, что пришло в каналы, пока
     * «Клипы» были открыты. hasMore выставляется, когда страница заполнена целиком.
     */
    NewerPage loadNewerPage(ArrayList<ChannelSnapshot> channels, Cursor newest) {
        NewerPage page = new NewerPage();
        page.first.set(newest.date, newest.uid, newest.mid);
        try {
            ArrayList<Long> dialogIds = new ArrayList<>(channels.size());
            for (int i = 0; i < channels.size(); i++) {
                dialogIds.add(channels.get(i).dialogId);
            }
            MessagesStorage messagesStorage = MessagesStorage.getInstance(currentAccount);
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT data, mid, date, uid FROM media_v4 WHERE uid IN (");
            sql.append(TextUtils.join(",", dialogIds));
            sql.append(") AND type IN (");
            sql.append(ReelsMediaPolicy.mediaTypesSql());
            sql.append(") AND mid > 0");
            appendCursorBound(sql, newest, Direction.NEWER, false);
            sql.append(" ORDER BY date ASC, uid ASC, mid ASC LIMIT ");
            sql.append(NEWER_PAGE_SIZE);

            int rowCount = 0;
            SQLiteCursor sqlCursor = messagesStorage.getDatabase().queryFinalized(sql.toString(), new Object[0]);
            try {
                while (sqlCursor.next()) {
                    rowCount++;
                    page.first.set(sqlCursor.intValue(2), sqlCursor.longValue(3), sqlCursor.intValue(1));
                    TLRPC.Message message = readMessage(sqlCursor);
                    if (message != null) {
                        page.messages.add(message);
                        MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                    }
                }
            } finally {
                sqlCursor.dispose();
            }
            page.hasMore = rowCount == NEWER_PAGE_SIZE;
            if (!usersToLoad.isEmpty()) {
                messagesStorage.getUsersInternal(usersToLoad, page.users);
            }
            if (!chatsToLoad.isEmpty()) {
                messagesStorage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return page;
    }

    /**
     * Перечитывает уже показанный отрезок между двумя курсорами включительно. Если отрезок
     * вырос сверху лимита, страница помечается truncated, и вызывающий пересобирает ленту.
     */
    WindowPage loadChannelWindow(ArrayList<Long> dialogIds, Cursor newest, Cursor oldest) {
        WindowPage page = new WindowPage();
        if (dialogIds.isEmpty() || newest.isEmpty() || oldest.isEmpty()) {
            return page;
        }
        try {
            MessagesStorage messagesStorage = MessagesStorage.getInstance(currentAccount);
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            StringBuilder sql = new StringBuilder("SELECT data, mid, date, uid FROM media_v4 WHERE uid IN (");
            sql.append(TextUtils.join(",", dialogIds));
            sql.append(") AND type IN (");
            sql.append(ReelsMediaPolicy.mediaTypesSql());
            sql.append(") AND mid > 0");
            appendCursorBound(sql, newest, Direction.OLDER, true);
            appendCursorBound(sql, oldest, Direction.NEWER, true);
            sql.append(" ORDER BY date DESC, uid DESC, mid DESC LIMIT ");
            sql.append(WINDOW_SIZE + 1);

            SQLiteCursor sqlCursor = messagesStorage.getDatabase().queryFinalized(sql.toString(), new Object[0]);
            try {
                int rowCount = 0;
                while (sqlCursor.next()) {
                    rowCount++;
                    if (rowCount > WINDOW_SIZE) {
                        page.truncated = true;
                        break;
                    }
                    TLRPC.Message message = readMessage(sqlCursor);
                    if (message != null) {
                        page.messages.add(message);
                        MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                    }
                }
            } finally {
                sqlCursor.dispose();
            }
            if (!usersToLoad.isEmpty()) {
                messagesStorage.getUsersInternal(usersToLoad, page.users);
            }
            if (!chatsToLoad.isEmpty()) {
                messagesStorage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return page;
    }
}
