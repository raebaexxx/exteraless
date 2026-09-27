package app.exteraless.reels.ui;

import android.content.Context;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.Components.Reactions.ReactionsEffectOverlay;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.util.ArrayList;
import java.util.HashSet;

import app.exteraless.appearance.MainTabsUiHelper;
import app.exteraless.reels.ReelsConfig;
import app.exteraless.reels.ReelsController;

/**
 * Экран «Клипов»: вертикальный полноэкранный пейджер с роликами из каналов.
 *
 * <p>Медиа рисует общий слой плеера поверх пейджера, а не сами страницы: текстура одна
 * на весь экран, поэтому перелистывание ничего не пересоздаёт. Страницы отвечают только
 * за постер и подписи.
 *
 * <p>Нижний отступ под панель вкладок добавляется к системному — так последний ролик
 * не уезжает под панель, а медиа при этом остаётся во всю высоту экрана.
 */
public class ReelsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate,
        MainTabsActivity.TabFragmentDelegate, ReelsPageView.Delegate, ReelsPlayerController.Callback {

    private static final String ARG_HAS_MAIN_TABS = "hasMainTabs";

    private static final int PROGRESS_INTERVAL_MS = 100;
    private static final int LOAD_AHEAD_PAGES = 2;
    private static final long PREFETCH_DELAY_MS = 250L;

    private final Runnable progressRunnable = this::updateProgress;
    private final Runnable postDelayedAttach = this::attachPlayerForCurrentPage;

    private RecyclerListView listView;
    private ReelsAdapter adapter;
    private PagerSnapHelper snapHelper;
    private LinearLayoutManager layoutManager;

    private FrameLayout rootLayout;
    private FrameLayout playerLayer;
    private TextureView textureView;
    private LinearLayout emptyView;
    private TextView emptyTitle;
    private TextView emptySubtitle;
    private TextView emptyButton;
    private ImageView settingsButton;

    private ReelsPlayerController player;
    private ReelsController controller;

    private boolean hasMainTabs;
    private boolean viewportActive;
    private boolean uiActiveHeld;
    private boolean uiResumedHeld;
    private int currentPage = -1;
    private int previousPage = -1;
    private int lastConfigGeneration;
    private int topInset;
    private int bottomInset;
    private boolean progressScheduled;

    public ReelsActivity() {
        this(null);
    }

    public ReelsActivity(Bundle args) {
        super(args);
    }

    /**
     * Открывает «Клипы»: на планшете — в правой колонке поверх очищенного стека,
     * на телефоне — обычным переходом.
     */
    public static void presentReels(BaseFragment fragment) {
        final LaunchActivity launchActivity = LaunchActivity.instance;
        if (!AndroidUtilities.isTablet() || launchActivity == null || launchActivity.getRightActionBarLayout() == null) {
            if (fragment != null) {
                fragment.presentFragment(new ReelsActivity());
            }
            return;
        }
        final INavigationLayout rightLayout = launchActivity.getRightActionBarLayout();
        if (rightLayout.getLastFragment() instanceof ReelsActivity) {
            return;
        }
        if (!rightLayout.getFragmentStack().isEmpty()) {
            while (rightLayout.getFragmentStack().size() - 1 > 0) {
                rightLayout.removeFragmentFromStack(rightLayout.getFragmentStack().get(0));
            }
            rightLayout.closeLastFragment(false);
        }
        rightLayout.presentFragment(new INavigationLayout.NavigationParams(new ReelsActivity())
                .setNoAnimation(true)
                .forceRightLayout());
    }

    @Override
    public boolean onFragmentCreate() {
        hasMainTabs = arguments != null && arguments.getBoolean(ARG_HAS_MAIN_TABS, false);
        viewportActive = !hasMainTabs;
        controller = ReelsController.getInstance(currentAccount);
        player = new ReelsPlayerController(currentAccount);
        player.setCallback(this);
        lastConfigGeneration = ReelsConfig.getInstance(currentAccount).getGeneration();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.reelsNeedReload);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.reelsDataUpdated);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.reelsTabVisibleToggled);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(progressRunnable);
        AndroidUtilities.cancelRunOnUIThread(postDelayedAttach);
        progressScheduled = false;
        if (player != null) {
            player.setCallback(null);
            player.release();
            player = null;
        }
        if (uiResumedHeld) {
            uiResumedHeld = false;
            controller.setUiResumed(false);
        }
        if (uiActiveHeld) {
            uiActiveHeld = false;
            controller.setUiActive(false);
        }
        Bulletin.removeDelegate(this);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.reelsNeedReload);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.reelsDataUpdated);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.reelsTabVisibleToggled);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        hasMainTabs = arguments != null && arguments.getBoolean(ARG_HAS_MAIN_TABS, false);
        actionBar.setAddToContainer(false);
        actionBar.setVisibility(View.GONE);

        rootLayout = new FrameLayout(context);
        rootLayout.setBackgroundColor(Color.BLACK);
        fragmentView = rootLayout;

        listView = new RecyclerListView(context, getResourceProvider());
        listView.setLayoutManager(createLayoutManager(context));
        listView.setOverScrollMode(RecyclerListView.OVER_SCROLL_NEVER);
        listView.setClipToPadding(false);
        listView.setVerticalScrollBarEnabled(false);
        if (listView.getItemAnimator() != null) {
            listView.setItemAnimator(null);
        }
        adapter = new ReelsAdapter(this);
        listView.setAdapter(adapter);
        listView.setItemViewCacheSize(2);
        listView.setRecycledViewPool(new RecyclerView.RecycledViewPool());
        rootLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(listView);

        playerLayer = new FrameLayout(context);
        playerLayer.setVisibility(View.INVISIBLE);
        rootLayout.addView(playerLayer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        textureView = new TextureView(context);
        playerLayer.addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        settingsButton = new ImageView(context);
        settingsButton.setImageResource(R.drawable.msg_settings);
        settingsButton.setColorFilter(0xFFFFFFFF);
        settingsButton.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        settingsButton.setBackground(circleBackground(0x33000000));
        settingsButton.setContentDescription(LocaleController.getString(R.string.ReelsSettings));
        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(AndroidUtilities.dp(36), AndroidUtilities.dp(36), Gravity.TOP | Gravity.START);
        settingsParams.leftMargin = AndroidUtilities.dp(10);
        settingsParams.topMargin = AndroidUtilities.dp(8);
        rootLayout.addView(settingsButton, settingsParams);
        settingsButton.setOnClickListener(v -> presentFragment(new ReelsSettingsActivity()));

        createEmptyView(context);
        rootLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView view, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateCurrentPage();
                }
            }

            @Override
            public void onScrolled(RecyclerView view, int dx, int dy) {
                if (dy == 0) {
                    return;
                }
                updateCurrentPage();
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, this::applyWindowInsets);

        Bulletin.addDelegate(this, new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                return 0;
            }

            @Override
            public int getTopOffset(int tag) {
                return AndroidUtilities.statusBarHeight;
            }
        });

        if (!uiActiveHeld) {
            uiActiveHeld = true;
            controller.setUiActive(true);
        }
        showEmptyState();
        return rootLayout;
    }

    private RecyclerView.LayoutManager createLayoutManager(Context context) {
        layoutManager = new LinearLayoutManager(context, RecyclerView.VERTICAL, false);
        layoutManager.setItemPrefetchEnabled(true);
        return layoutManager;
    }

    private void createEmptyView(Context context) {
        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyView.setPadding(AndroidUtilities.dp(32), AndroidUtilities.dp(32), AndroidUtilities.dp(32), AndroidUtilities.dp(32));

        emptyTitle = new TextView(context);
        emptyTitle.setTextColor(0xFFFFFFFF);
        emptyTitle.setTextSize(17);
        emptyTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyView.addView(emptyTitle, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        emptySubtitle = new TextView(context);
        emptySubtitle.setTextColor(0x99FFFFFF);
        emptySubtitle.setTextSize(14);
        emptySubtitle.setGravity(Gravity.CENTER);
        emptySubtitle.setPadding(0, AndroidUtilities.dp(8), 0, 0);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        subtitleParams.topMargin = AndroidUtilities.dp(8);
        emptyView.addView(emptySubtitle, subtitleParams);

        emptyButton = new TextView(context);
        emptyButton.setTextColor(0xFFFFFFFF);
        emptyButton.setTextSize(15);
        emptyButton.setGravity(Gravity.CENTER);
        emptyButton.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(12), AndroidUtilities.dp(22), AndroidUtilities.dp(12));
        emptyButton.setBackground(background(0x33FFFFFF, AndroidUtilities.dp(20)));
        emptyButton.setOnClickListener(v -> presentFragment(new ReelsSettingsActivity()));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        buttonParams.topMargin = AndroidUtilities.dp(20);
        emptyView.addView(emptyButton, buttonParams);
    }

    private static GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static GradientDrawable circleBackground(int color) {
        GradientDrawable drawable = background(color, 0);
        drawable.setShape(GradientDrawable.OVAL);
        return drawable;
    }

    private WindowInsetsCompat applyWindowInsets(View view, WindowInsetsCompat insets) {
        Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
        int tabsHeight = hasMainTabs ? AndroidUtilities.dp(MainTabsUiHelper.getTabsViewHeightDp()) : 0;
        topInset = systemBars.top;
        bottomInset = systemBars.bottom + tabsHeight;

        if (emptyView != null) {
            emptyView.setPadding(AndroidUtilities.dp(32), topInset + AndroidUtilities.dp(32),
                    AndroidUtilities.dp(32), bottomInset + AndroidUtilities.dp(32));
        }
        if (settingsButton != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) settingsButton.getLayoutParams();
            params.topMargin = topInset + AndroidUtilities.dp(8);
            settingsButton.setLayoutParams(params);
        }
        applyInsetsToPages();
        return insets;
    }

    private void applyInsetsToPages() {
        if (listView == null || adapter == null) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof ReelsPageView) {
                ((ReelsPageView) child).setInsets(topInset, bottomInset);
            }
        }
    }

    // ---- пагинация ----

    /**
     * Страница, которая сейчас в экране. Берём её у снап-хелпера: он и есть источник
     * правды о том, к какой странице список прилипнет.
     */
    private int computeCurrentPage() {
        if (layoutManager == null || snapHelper == null) {
            return -1;
        }
        View snapView = snapHelper.findSnapView(layoutManager);
        if (snapView == null) {
            return -1;
        }
        int position = layoutManager.getPosition(snapView);
        if (position == RecyclerView.NO_POSITION || position >= adapter.getItemCountSafe()) {
            return -1;
        }
        return position;
    }

    private void updateCurrentPage() {
        int page = computeCurrentPage();
        if (page < 0) {
            return;
        }
        if (page == currentPage) {
            return;
        }
        currentPage = page;
        onPageChanged(page);
    }

    private void onPageChanged(int page) {
        ReelsPageView previous = findPageView(previousPage);
        if (previous != null) {
            previous.setActive(false);
        }
        previousPage = currentPage;
        ReelsPageView view = findPageView(page);
        MessageObject message = adapter.getItem(page);
        if (message != null) {
            ReelsConfig.getInstance(currentAccount).setLastPosition(message.getDialogId(), message.getRealId());
            if (ReelsConfig.getInstance(currentAccount).getMarkSeen()) {
                controller.onPageSeen(message);
            }
        }
        AndroidUtilities.cancelRunOnUIThread(postDelayedAttach);
        AndroidUtilities.runOnUIThread(postDelayedAttach, PREFETCH_DELAY_MS);
        if (view != null) {
            view.setActive(true);
        }
        maybeLoadMore(page);
    }

    private void maybeLoadMore(int page) {
        if (controller.isLoading() || controller.isEndReached()) {
            return;
        }
        if (adapter.getItemCountSafe() - page <= LOAD_AHEAD_PAGES) {
            controller.loadOlder();
        }
    }

    private ReelsPageView findPageView(int position) {
        if (listView == null) {
            return null;
        }
        RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(position);
        if (holder instanceof ReelsAdapter.PageHolder) {
            View view = holder.itemView;
            return view instanceof ReelsPageView ? (ReelsPageView) view : null;
        }
        return null;
    }

    // ---- плеер ----

    private void attachPlayerForCurrentPage() {
        if (player == null || listView == null || !viewportActive) {
            return;
        }
        ReelsPageView page = findPageView(currentPage);
        if (page == null) {
            return;
        }
        MessageObject message = page.getMessage();
        if (message == null) {
            return;
        }
        if (!ReelsConfig.getInstance(currentAccount).getAutoplay()) {
            // Автозапуск выключен: показываем постер и гасим прошлый ролик, иначе он
            // останется висеть поверх картинки.
            player.detach();
            playerLayer.setVisibility(View.INVISIBLE);
            page.showMedia();
            return;
        }
        page.setMuted(player.isMuted());
        boolean attached = player.attach(message, textureView, playerLayer);
        if (attached) {
            page.hideMedia();
            playerLayer.setVisibility(View.VISIBLE);
        } else {
            // Фотографии и GIF без документа: оставляем постер, но он уже загружен.
            page.showMedia();
            playerLayer.setVisibility(View.INVISIBLE);
        }
        if (!page.isPausedByUser()) {
            player.play();
        }
        updateProgress();
    }

    @Override
    public void onVideoSize(int width, int height, int rotation, float pixelWidthHeightRatio) {
        if (playerLayer == null || width <= 0 || height <= 0) {
            return;
        }
        if (rotation == 90 || rotation == 270) {
            int temp = width;
            width = height;
            height = temp;
        }
        int viewWidth = playerLayer.getWidth();
        int viewHeight = playerLayer.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        float videoRatio = (width * pixelWidthHeightRatio) / (float) height;
        float viewRatio = viewWidth / (float) viewHeight;
        boolean photo = isCurrentPagePhoto();
        // Фото в 4:3 нельзя кропить до 9:16 — обрежется текст мема, поэтому фотографии
        // вписываем целиком, а ролики кадрируем по экрану, как в Reels.
        float scaleX = photo ? Math.min(1f, videoRatio / viewRatio) : Math.max(1f, videoRatio / viewRatio);
        float scaleY = photo ? Math.min(1f, viewRatio / videoRatio) : Math.max(1f, viewRatio / videoRatio);
        textureView.setScaleX(scaleX);
        textureView.setScaleY(scaleY);
    }

    private boolean isCurrentPagePhoto() {
        ReelsPageView page = findPageView(currentPage);
        return page != null && page.getMessage() != null && page.getMessage().isPhoto();
    }

    @Override
    public void onRenderedFirstFrame() {
        if (playerLayer != null) {
            playerLayer.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onStateChanged(boolean playing, boolean buffering) {
        ReelsPageView page = findPageView(currentPage);
        if (page != null) {
            page.showBuffering(buffering);
        }
    }

    @Override
    public void onError() {
        ReelsPageView page = findPageView(currentPage);
        if (page != null) {
            page.showMedia();
            page.showBuffering(false);
        }
        if (playerLayer != null) {
            playerLayer.setVisibility(View.INVISIBLE);
        }
        // Битый ролик не должен держать ленту: уходим на следующий, иначе пользователь
        // застрянет на одном кадре без объяснений.
        AndroidUtilities.runOnUIThread(() -> {
            if (viewportActive && currentPage + 1 < adapter.getItemCountSafe()) {
                scrollToPage(currentPage + 1);
            }
        }, 800L);
    }

    private void scrollToPage(int page) {
        if (listView == null || page < 0 || page >= adapter.getItemCountSafe()) {
            return;
        }
        layoutManager.scrollToPositionWithOffset(page, 0);
        currentPage = page;
        onPageChanged(page);
        scheduleCurrentPageUpdate();
    }

    private void updateProgress() {
        progressScheduled = false;
        ReelsPageView page = findPageView(currentPage);
        if (page == null || player == null || !viewportActive) {
            return;
        }
        long duration = player.getDuration();
        if (duration > 0) {
            float progress = player.getPosition() / (float) duration;
            float buffered = player.getBufferedPercentage() / 100f;
            page.setProgress(progress, buffered);
        }
        if (player.isPlaying()) {
            scheduleProgress();
        }
    }

    private void scheduleProgress() {
        if (progressScheduled) {
            return;
        }
        progressScheduled = true;
        AndroidUtilities.runOnUIThread(progressRunnable, PROGRESS_INTERVAL_MS);
    }

    // ---- жесты и действия ----

    @Override
    public void onSingleTap(ReelsPageView page) {
        if (player == null) {
            return;
        }
        boolean paused = !player.isPlaying();
        page.setPausedByUser(paused);
        if (paused) {
            player.pause();
        } else {
            player.play();
        }
        page.showPlayIcon(paused);
    }

    @Override
    public void onDoubleTap(ReelsPageView page, float x, float y) {
        toggleReaction(page, true);
    }

    @Override
    public void onAction(ReelsPageView page, int action) {
        switch (action) {
            case ReelsPageView.ACTION_REACT:
                toggleReaction(page, false);
                break;
            case ReelsPageView.ACTION_SHARE:
                sharePost(page.getMessage());
                break;
            case ReelsPageView.ACTION_OPEN_CHAT:
                openInChat(page.getMessage());
                break;
            case ReelsPageView.ACTION_SAVE:
                saveToGallery(page.getMessage());
                break;
            case ReelsPageView.ACTION_HIDE_CHANNEL:
                hideChannel(page.getMessage());
                break;
            case ReelsPageView.ACTION_REACTIONS_MENU:
                showReactionPicker(page, true);
                break;
            case ReelsPageView.ACTION_MUTE:
                toggleMute();
                break;
        }
    }

    private void toggleMute() {
        if (player == null) {
            return;
        }
        boolean muted = !player.isMuted();
        player.setMuted(muted);
        ReelsConfig.getInstance(currentAccount).setStartMuted(muted);
        ReelsPageView page = findPageView(currentPage);
        if (page != null) {
            page.setMuted(muted);
        }
        // playerDidStartPlaying прилетает только от немаутого плеера, поэтому чужие
        // проигрыватели (чат, музыка) гасим здесь, а не полагаемся на уведомление.
        MediaController.getInstance().cleanupPlayer(true, true);
    }

    @Override
    public void onChannelClick(ReelsPageView page) {
        openInChat(page.getMessage());
    }

    @Override
    public void onCaptionClick(ReelsPageView page) {
        // Подпись умеет разворачиваться сама; экрану тут делать нечего.
    }

    private void showReactionPicker(ReelsPageView page, boolean asMenu) {
        MessageObject message = page.getMessage();
        if (message == null) {
            return;
        }
        long dialogId = message.getDialogId();
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> reactions = ReelsReactions.availableReactions(currentAccount, dialogId);
        if (reactions.isEmpty()) {
            return;
        }
        if (reactions.size() == 1 || !asMenu) {
            sendReaction(page, reactions.get(0), false);
            return;
        }
        View anchor = page;
        ItemOptions options = ItemOptions.makeOptions(this, anchor);
        for (int i = 0; i < reactions.size() && i < 8; i++) {
            ReactionsLayoutInBubble.VisibleReaction reaction = reactions.get(i);
            options.add(reaction.emojicon != null ? reaction.emojicon : "⭐️",
                    () -> sendReaction(page, reaction, false));
        }
        options.setGravity(Gravity.RIGHT);
        options.show();
    }

    /**
     * Ставит или снимает реакцию. Двойной тап — «большая» реакция с анимацией,
     * как в самом мессенджере; кнопка в панели — обычная.
     */
    private void toggleReaction(ReelsPageView page, boolean asBigReaction) {
        MessageObject message = page.getMessage();
        if (message == null) {
            return;
        }
        long dialogId = message.getDialogId();
        if (ReelsReactions.availableReactions(currentAccount, dialogId).isEmpty()) {
            return;
        }
        ReactionsLayoutInBubble.VisibleReaction reaction = page.getFavoriteReaction();
        if (reaction == null) {
            showReactionPicker(page, true);
            return;
        }
        sendReaction(page, reaction, asBigReaction);
        if (asBigReaction) {
            ReactionsEffectOverlay.removeCurrent(false);
            ReactionsEffectOverlay.show(this, null, page, null, 0, 0, reaction, currentAccount,
                    ReactionsEffectOverlay.SHORT_ANIMATION);
        }
    }

    private void sendReaction(ReelsPageView page, ReactionsLayoutInBubble.VisibleReaction reaction,
                              boolean big) {
        MessageObject message = page.getMessage();
        if (message == null || reaction == null) {
            return;
        }
        boolean added = message.selectReaction(reaction, big, big);
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> chosen = new ArrayList<>(message.getChoosenReactions());
        getSendMessagesHelper().sendReaction(message, chosen, added ? reaction : null, big, true, this, null);
        page.refreshReaction();
    }

    private void sharePost(MessageObject message) {
        if (message == null || getParentActivity() == null) {
            return;
        }
        ArrayList<MessageObject> messages = new ArrayList<>();
        messages.add(message);
        showDialog(new ShareAlert(getParentActivity(), null, messages, null, null,
                true, null, null, true, false, false, null, getResourceProvider()));
    }

    private void openInChat(MessageObject message) {
        if (message == null) {
            return;
        }
        presentFragment(ChatActivity.of(message.getDialogId(), message.getRealId()));
    }

    private void saveToGallery(MessageObject message) {
        if (message == null || getParentActivity() == null) {
            return;
        }
        ArrayList<MessageObject> messages = new ArrayList<>();
        messages.add(message);
        MediaController.saveFilesFromMessages(getParentActivity(), getAccountInstance(), messages, saved -> {
            if (saved != 0 && getParentActivity() != null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                        LocaleController.getString(R.string.ReelsSaved)).show();
            }
        });
    }

    private void hideChannel(MessageObject message) {
        if (message == null) {
            return;
        }
        final long dialogId = message.getDialogId();
        TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
        final String title = chat != null ? chat.title : "";
        ReelsConfig.getInstance(currentAccount).setIncluded(dialogId, false);
        controller.applyConfigChange(truncated -> {
            NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.reelsNeedReload, truncated);
        });
        BulletinFactory.of(this).createUndoBulletin(
                        AndroidUtilities.replaceTags(LocaleController.formatString(R.string.ReelsChannelHidden, title)),
                        () -> {
                            ReelsConfig.getInstance(currentAccount).setIncluded(dialogId, true);
                            controller.applyConfigChange(truncated ->
                                    NotificationCenter.getInstance(currentAccount)
                                            .postNotificationName(NotificationCenter.reelsNeedReload, truncated));
                        },
                        null)
                .show();
    }

    // ---- пустое состояние ----

    private void showEmptyState() {
        if (emptyView == null || adapter == null) {
            return;
        }
        if (adapter.getItemCountSafe() > 0) {
            emptyView.setVisibility(View.GONE);
            return;
        }
        emptyView.setVisibility(View.VISIBLE);
        if (!ReelsConfig.getInstance(currentAccount).hasIncludedChannels()) {
            emptyTitle.setText(R.string.ReelsNoChannelsTitle);
            emptySubtitle.setText(R.string.ReelsNoChannelsInfo);
        } else {
            emptyTitle.setText(R.string.ReelsNoPostsTitle);
            emptySubtitle.setText(R.string.ReelsNoPostsInfo);
        }
        emptyButton.setText(R.string.ReelsChooseChannels);
    }

    // ---- данные ----

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (adapter == null || listView == null) {
            // Экран создан, но вьюхи ещё нет: окно перечитается в onResume.
            return;
        }
        if (id == NotificationCenter.reelsDataUpdated) {
            boolean toStart = args.length > 1 && Boolean.TRUE.equals(args[1]);
            if (toStart) {
                applyPrepend();
            } else {
                applyFullRefresh();
            }
        } else if (id == NotificationCenter.reelsNeedReload) {
            boolean truncated = args.length > 0 && Boolean.TRUE.equals(args[0]);
            if (truncated) {
                adapter.clear();
                currentPage = -1;
                restoredPosition = false;
            }
            bindChatsAndReactions();
            controller.loadInitial();
        } else if (id == NotificationCenter.reelsTabVisibleToggled) {
            // Экран мог остаться в кэше вкладок, пока её скрывали: перечитываем настройки.
            onConfigChanged();
        }
    }

    /**
     * Перечитывает окно целиком. Текущий ролик запоминаем по паре (диалог, id), а не по
     * индексу: вставка и удаление сдвигают индексы, а смотреть пользователь хочет тот же
     * ролик.
     */
    private void applyFullRefresh() {
        MessageObject current = currentPage >= 0 ? adapter.getItem(currentPage) : null;
        adapter.setItems(controller.getMessages());
        bindChatsAndReactions();
        int target = indexOf(current);
        if (target < 0) {
            target = indexOfSavedPosition();
        }
        if (target >= 0) {
            layoutManager.scrollToPositionWithOffset(target, 0);
        } else if (adapter.getItemCountSafe() > 0) {
            layoutManager.scrollToPositionWithOffset(0, 0);
        }
        currentPage = -1;
        showEmptyState();
        scheduleCurrentPageUpdate();
    }

    /**
     * Список обновился, но вьюхи ещё не размещены: с позиции, взятой до обновления, они
     * сдвинутся. Поэтому страницу пересчитываем ещё раз после кадра.
     */
    private void scheduleCurrentPageUpdate() {
        updateCurrentPage();
        AndroidUtilities.runOnUIThread(this::updateCurrentPage, 60);
    }

    private int indexOf(MessageObject message) {
        if (message == null) {
            return -1;
        }
        ArrayList<MessageObject> messages = controller.getMessages();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject candidate = messages.get(i);
            if (candidate.getDialogId() == message.getDialogId() && candidate.getRealId() == message.getRealId()) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfSavedPosition() {
        if (restoredPosition) {
            return -1;
        }
        restoredPosition = true;
        ReelsConfig config = ReelsConfig.getInstance(currentAccount);
        long dialogId = config.getLastDialogId();
        int messageId = config.getLastMessageId();
        if (dialogId == 0 || messageId == 0) {
            return -1;
        }
        ArrayList<MessageObject> messages = controller.getMessages();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message.getDialogId() == dialogId && message.getRealId() == messageId) {
                return i;
            }
        }
        return -1;
    }

    private void applyPrepend() {
        if (adapter == null || listView == null) {
            return;
        }
        MessageObject current = currentPage >= 0 ? adapter.getItem(currentPage) : null;
        adapter.setItems(controller.getMessages());
        bindChatsAndReactions();
        // Вставка сверху сдвинула всё: возвращаем тот же ролик в экран.
        int target = indexOf(current);
        layoutManager.scrollToPositionWithOffset(Math.max(0, target), 0);
        currentPage = -1;
        scheduleCurrentPageUpdate();
    }

    private void bindChatsAndReactions() {
        if (controller == null) {
            return;
        }
        ArrayList<MessageObject> messages = controller.getMessages();
        HashSet<Long> seen = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message == null) {
                continue;
            }
            long dialogId = message.getDialogId();
            if (!seen.add(dialogId)) {
                continue;
            }
            TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            if (chat != null) {
                adapter.setChat(dialogId, chat);
            }
            ArrayList<ReactionsLayoutInBubble.VisibleReaction> reactions =
                    ReelsReactions.availableReactions(currentAccount, dialogId);
            if (!reactions.isEmpty()) {
                adapter.setReaction(dialogId, reactions.get(0));
            }
        }
    }

    private boolean restoredPosition;

    private void onConfigChanged() {
        int generation = ReelsConfig.getInstance(currentAccount).getGeneration();
        if (generation == lastConfigGeneration) {
            return;
        }
        lastConfigGeneration = generation;
        adapter.clear();
        currentPage = -1;
        restoredPosition = false;
        controller.applyConfigChange(truncated -> {
            NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.reelsNeedReload, truncated);
        });
    }

    // ---- жизненный цикл ----

    @Override
    public void onResume() {
        super.onResume();
        if (!uiResumedHeld) {
            uiResumedHeld = true;
            controller.setUiResumed(true);
        }
        onConfigChanged();
        if (adapter.getItemCountSafe() == 0) {
            controller.loadInitial();
        }
        if (viewportActive) {
            updateCurrentPage();
            attachPlayerForCurrentPage();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (player != null) {
            player.pause();
        }
        AndroidUtilities.cancelRunOnUIThread(progressRunnable);
        progressScheduled = false;
        if (uiResumedHeld) {
            uiResumedHeld = false;
            controller.setUiResumed(false);
        }
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        viewportActive = true;
        updateCurrentPage();
        attachPlayerForCurrentPage();
    }

    @Override
    public void onBecomeFullyHidden() {
        viewportActive = false;
        if (player != null) {
            player.detach();
        }
        if (playerLayer != null) {
            playerLayer.setVisibility(View.INVISIBLE);
        }
        super.onBecomeFullyHidden();
    }

    @Override
    public void onTransitionAnimationStart(boolean isOpen, boolean backward) {
        if (hasMainTabs) {
            viewportActive = false;
            if (player != null) {
                player.pause();
            }
        }
        super.onTransitionAnimationStart(isOpen, backward);
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (hasMainTabs) {
            viewportActive = isOpen;
            if (isOpen) {
                updateCurrentPage();
                attachPlayerForCurrentPage();
            }
        }
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        // Лента листается по вертикали, горизонтальный свайп должен уходить вкладкам.
        return true;
    }

    @Override
    public void onParentScrollToTop() {
        scrollToPage(0);
    }

    @Override
    public BlurredBackgroundSourceRenderNode getGlassSource() {
        return null;
    }

    @Override
    public void onParentBecomeFullyVisible() {
        attachPlayerForCurrentPage();
    }

    @Override
    public ArrayList<org.telegram.ui.ActionBar.ThemeDescription> getThemeDescriptions() {
        return new ArrayList<>();
    }
}
