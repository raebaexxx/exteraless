package app.exteraless.reels.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.reels.ReelsConfig;
import app.exteraless.reels.ReelsController;

/**
 * Экран настроек «Клипов»: переключатели поведения и список каналов-источников.
 *
 * <p>Список каналов — тот же, что и у ленты (это «Лента» показывает все каналы, а
 * «Клипы» — выбранные), но настройки отдельные: снятая галочка здесь не трогает ленту.
 */
public class ReelsSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int MENU_SEARCH = 0;
    private static final int MENU_SELECT_ALL = 1;
    private static final int MENU_DESELECT_ALL = 2;

    private static final int ID_TAB = Integer.MAX_VALUE - 1;
    private static final int ID_AUTOPLAY = Integer.MAX_VALUE - 2;
    private static final int ID_START_MUTED = Integer.MAX_VALUE - 3;
    private static final int ID_SHOW_PHOTOS = Integer.MAX_VALUE - 4;
    private static final int ID_MARK_SEEN = Integer.MAX_VALUE - 5;
    private static final int ID_INCLUDE_ARCHIVED = Integer.MAX_VALUE - 6;

    private static final Comparator<TLRPC.Chat> BY_TITLE =
            Comparator.comparing(ReelsSettingsActivity::sortKey);

    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();

    private UniversalRecyclerView listView;
    private ActionBarMenuItem otherItem;
    private String query;
    private boolean searching;

    private static String sortKey(TLRPC.Chat chat) {
        return chat.title == null ? "" : chat.title.toLowerCase(Locale.ROOT);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(getString(R.string.ReelsSettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SELECT_ALL) {
                    setAllIncluded(true);
                } else if (id == MENU_DESELECT_ALL) {
                    setAllIncluded(false);
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_SEARCH, R.drawable.outline_header_search)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {
                        searching = true;
                        if (otherItem != null) {
                            otherItem.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onSearchCollapse() {
                        searching = false;
                        query = null;
                        if (otherItem != null) {
                            otherItem.setVisibility(View.VISIBLE);
                        }
                        update();
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        query = editText.getText().toString().trim().toLowerCase(Locale.ROOT);
                        update();
                    }
                })
                .setSearchFieldHint(getString(R.string.Search));

        otherItem = menu.addItem(2, R.drawable.ic_ab_other);
        otherItem.addSubItem(MENU_SELECT_ALL, R.drawable.msg_select, getString(R.string.SelectAll));
        otherItem.addSubItem(MENU_DESELECT_ALL, R.drawable.msg_cancel, getString(R.string.DeselectAll));

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        listView.setClipToPadding(false);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);

        fragmentView = contentView;
        reloadChannels();
        return fragmentView;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(0, 0, 0, bottom);
        }
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.reelsNeedReload);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.reelsNeedReload);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.reelsNeedReload) {
            reloadChannels();
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!searching) {
            return super.onBackPressed(invoked);
        }
        if (invoked) {
            actionBar.closeSearchField();
        }
        return false;
    }

    private void reloadChannels() {
        ReelsController.getInstance(currentAccount).loadChannels((loaded, includedCount) -> {
            channels.clear();
            channels.addAll(loaded);
            channels.sort(BY_TITLE);
            update();
        });
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void setAllIncluded(boolean included) {
        ArrayList<Long> dialogIds = new ArrayList<>(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            dialogIds.add(-channels.get(i).id);
        }
        if (!included) {
            dialogIds.clear();
        }
        ReelsConfig.getInstance(currentAccount).setIncludedChannels(dialogIds);
        applyChannelsChange();
    }

    private void applyChannelsChange() {
        ReelsController.getInstance(currentAccount).applyConfigChange(truncated ->
                NotificationCenter.getInstance(currentAccount)
                        .postNotificationName(NotificationCenter.reelsNeedReload, truncated));
        update();
    }

    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        ReelsConfig config = ReelsConfig.getInstance(currentAccount);
        boolean noQuery = TextUtils.isEmpty(query);
        if (noQuery) {
            items.add(UItem.asHeader(getString(R.string.General)));
            items.add(UItem.asCheck(ID_TAB, getString(R.string.ReelsBottomTab),
                    getString(R.string.ReelsBottomTabInfo), true)
                    .setChecked(AppearanceConfig.showReelsTab()));
            items.add(UItem.asCheck(ID_AUTOPLAY, getString(R.string.ReelsAutoplay),
                    getString(R.string.ReelsAutoplayInfo), true)
                    .setChecked(config.getAutoplay()));
            items.add(UItem.asCheck(ID_START_MUTED, getString(R.string.ReelsStartMuted),
                    getString(R.string.ReelsStartMutedInfo), true)
                    .setChecked(config.getStartMuted()));
            items.add(UItem.asCheck(ID_SHOW_PHOTOS, getString(R.string.ReelsShowPhotos),
                    getString(R.string.ReelsShowPhotosInfo), true)
                    .setChecked(config.getShowPhotos()));
            items.add(UItem.asCheck(ID_MARK_SEEN, getString(R.string.ReelsMarkSeen),
                    getString(R.string.ReelsMarkSeenInfo), true)
                    .setChecked(config.getMarkSeen()));
            items.add(UItem.asCheck(ID_INCLUDE_ARCHIVED, getString(R.string.ReelsIncludeArchived),
                    getString(R.string.ReelsIncludeArchivedInfo), true)
                    .setChecked(config.getIncludeArchived()));
            items.add(UItem.asShadow(getString(R.string.ReelsSettingsInfo)));
        }

        ArrayList<UItem> shown = new ArrayList<>();
        ArrayList<UItem> hidden = new ArrayList<>();
        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat chat = channels.get(i);
            if (!noQuery && (chat.title == null || !chat.title.toLowerCase(Locale.ROOT).contains(query))) {
                continue;
            }
            boolean included = config.isIncluded(-chat.id);
            UItem item = UItem.asUserCheckbox(i + 1, chat).setChecked(included);
            (included ? shown : hidden).add(item);
        }

        if (!shown.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.ReelsShownChannels)));
            items.addAll(shown);
        }
        if (!hidden.isEmpty()) {
            if (!shown.isEmpty()) {
                items.add(UItem.asShadow((CharSequence) null));
            }
            items.add(UItem.asHeader(getString(R.string.ReelsHiddenChannels)));
            items.addAll(hidden);
        }
        if (noQuery) {
            items.add(UItem.asShadow(getString(R.string.ReelsChannelsInfo)));
        }
    }

    public void onClick(UItem item, View view, int position, float x, float y) {
        ReelsConfig config = ReelsConfig.getInstance(currentAccount);
        if (item.id == ID_TAB) {
            AppearanceConfig.showReelsTab.setConfigBool(!AppearanceConfig.showReelsTab.Bool());
            update();
            NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.reelsTabVisibleToggled);
            return;
        }
        if (item.id == ID_AUTOPLAY) {
            config.setAutoplay(!config.getAutoplay());
            update();
            return;
        }
        if (item.id == ID_START_MUTED) {
            config.setStartMuted(!config.getStartMuted());
            update();
            return;
        }
        if (item.id == ID_SHOW_PHOTOS) {
            config.setShowPhotos(!config.getShowPhotos());
            applyChannelsChange();
            return;
        }
        if (item.id == ID_MARK_SEEN) {
            config.setMarkSeen(!config.getMarkSeen());
            update();
            return;
        }
        if (item.id == ID_INCLUDE_ARCHIVED) {
            config.setIncludeArchived(!config.getIncludeArchived());
            reloadChannels();
            return;
        }
        if (item.object instanceof TLRPC.Chat) {
            TLRPC.Chat chat = (TLRPC.Chat) item.object;
            boolean checked = !item.checked;
            config.setIncluded(-chat.id, checked);
            setCheckedAndRefresh(item, checked);
            applyChannelsChange();
        }
    }

    /**
     * Ставим галочку сразу на ячейке, а потом пересобираем список: без этого канал
     * переезжает между секциями без анимации.
     */
    private void setCheckedAndRefresh(UItem item, boolean checked) {
        item.setChecked(checked);
        View cell = listView == null ? null : listView.findViewByItemId(item.id);
        if (cell instanceof CheckBoxCell) {
            ((CheckBoxCell) cell).setChecked(checked, true);
        }
    }
}
