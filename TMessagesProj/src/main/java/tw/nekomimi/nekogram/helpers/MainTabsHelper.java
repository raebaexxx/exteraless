package tw.nekomimi.nekogram.helpers;

import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.appearance.MainTabsUiHelper;

import org.telegram.messenger.UserConfig;
import org.telegram.ui.MainTabsActivity;

import xyz.nextalone.nagram.NaConfig;

public final class MainTabsHelper {
    public static final int MAIN_TABS_HEIGHT = 56;
    public static final int MAIN_TABS_HEIGHT_IOS = 60;
    public static final int MAIN_TABS_MARGIN = 8;
    public static final int MAIN_TABS_MARGIN_COMPACT = 4;
    public static final int FILTER_TABS_HEIGHT = 36;
    public static final int TAB_WIDTH = 80;
    public static final int TAB_PADDING = 4;

    /**
     * Порядок слотов нижней панели. Позиции не константы: часть вкладок можно скрыть,
     * а часть занимает один слот по очереди («Лента» или «Контакты»). Поэтому позиция
     * считается функцией от набора видимых вкладок, а {@link MainTabsActivity} спрашивает
     * её здесь, а не держит свои номера.
     *
     * <p>Порядок: «Чаты», «Клипы», «Лента»/«Контакты», «Звонки»/«Настройки», «Профиль».
     */
    public static final int SLOT_CHATS = 0;
    public static final int SLOT_REELS = 1;
    public static final int SLOT_FEED_OR_CONTACTS = 2;
    public static final int SLOT_CALLS_OR_SETTINGS = 3;
    public static final int SLOT_PROFILE = 4;

    private MainTabsHelper() {
    }

    public static boolean isMainTabsHideTitleStyle() {
        return NaConfig.INSTANCE.getMainTabsHideTitles().Bool();
    }

    public static int getMainTabsHeight() {
        if (isMainTabsHideTitleStyle()) {
            return FILTER_TABS_HEIGHT;
        }
        return MainTabsUiHelper.isIosNavigationBar() ? MAIN_TABS_HEIGHT_IOS : MAIN_TABS_HEIGHT;
    }

    public static int getMainTabsMargin() {
        return isMainTabsHideTitleStyle() ? MAIN_TABS_MARGIN_COMPACT : MAIN_TABS_MARGIN;
    }

    public static int getMainTabsHeightWithMargins() {
        return getMainTabsHeight() + getMainTabsMargin() * 2;
    }

    public static boolean isContactsTabHidden() {
        return NaConfig.INSTANCE.getMainTabsHideContacts().Bool();
    }

    public static boolean isCallsTabHidden() {
        return NaConfig.INSTANCE.getMainTabsHideCallsSettings().Bool();
    }

    public static boolean isCallsTabShown(int account) {
        return UserConfig.getInstance(account).showCallsTab && !isCallsTabHidden();
    }

    public static boolean isProfileTabHidden() {
        return NaConfig.INSTANCE.getMainTabsHideProfile().Bool();
    }

    public static boolean isFeedTabShown() {
        return AppearanceConfig.showFeedTab();
    }

    /** Отдельная вкладка «Клипы» — вторая по счёту, сразу после «Чатов». */
    public static boolean isReelsTabShown() {
        return AppearanceConfig.showReelsTab();
    }

    /**
     * Вкладка «Лента» включена. Как и раньше, она занимает слот «Контактов» независимо от
     * настройки «Контакты»: включил ленту — значит она вместо них.
     */
    public static boolean isFeedTabEnabled() {
        return isFeedTabShown();
    }

    public static boolean hasFeedOrContactsTab() {
        return !isContactsTabHidden() || isFeedTabEnabled();
    }

    public static boolean hasContactsOrFeedTab() {
        return hasFeedOrContactsTab();
    }

    public static int getChatsPosition() {
        return SLOT_CHATS;
    }

    public static int getReelsPosition() {
        return isReelsTabShown() ? SLOT_REELS : -1;
    }

    /**
     * Позиция слота, который занимает либо «Лента», либо «Контакты»;
     * -1, если слот не занят.
     */
    public static int getFeedOrContactsPosition() {
        if (!hasFeedOrContactsTab()) {
            return -1;
        }
        return isReelsTabShown() ? SLOT_FEED_OR_CONTACTS : SLOT_REELS;
    }

    public static int getCallsOrSettingsPosition() {
        // Слот идёт после всех вкладок, которые стоят перед ним; скрытые вкладки
        // места не занимают.
        int position = getChatsPosition() + 1;
        if (isReelsTabShown()) {
            position++;
        }
        if (hasFeedOrContactsTab()) {
            position++;
        }
        return position;
    }

    public static int getProfilePosition() {
        if (isProfileTabHidden()) {
            return -1;
        }
        return getCallsOrSettingsPosition() + 1;
    }

    public static int getFragmentsCount() {
        int count = MainTabsActivity.TABS_COUNT;
        if (!hasFeedOrContactsTab()) {
            count--;
        }
        if (isReelsTabShown()) {
            count++;
        }
        if (isProfileTabHidden()) {
            count--;
        }
        return count;
    }

    /**
     * Какой слот вкладки находится на указанной позиции пейджера, либо -1, если позиция
     * за пределами панели. Слоты всегда идут подряд, без дыр, поэтому достаточно сравнений.
     */
    public static int getSlotAtPosition(int position) {
        if (position == 0) {
            return SLOT_CHATS;
        }
        if (position < 0) {
            return -1;
        }
        int next = 1;
        if (isReelsTabShown()) {
            if (position == next++) {
                return SLOT_REELS;
            }
        }
        if (hasFeedOrContactsTab()) {
            if (position == next++) {
                return SLOT_FEED_OR_CONTACTS;
            }
        }
        if (position == next++) {
            return SLOT_CALLS_OR_SETTINGS;
        }
        if (!isProfileTabHidden()) {
            if (position == next) {
                return SLOT_PROFILE;
            }
        }
        return -1;
    }

    /**
     * Позиция слота в пейджере либо -1, если вкладка скрыта.
     */
    public static int getPositionForSlot(int slot) {
        switch (slot) {
            case SLOT_CHATS:
                return getChatsPosition();
            case SLOT_REELS:
                return getReelsPosition();
            case SLOT_FEED_OR_CONTACTS:
                return getFeedOrContactsPosition();
            case SLOT_CALLS_OR_SETTINGS:
                return getCallsOrSettingsPosition();
            case SLOT_PROFILE:
                return getProfilePosition();
            default:
                return -1;
        }
    }

    public static int getTabsViewWidth() {
        return TAB_WIDTH * getFragmentsCount() + (getMainTabsMargin() + TAB_PADDING) * 2;
    }
}
