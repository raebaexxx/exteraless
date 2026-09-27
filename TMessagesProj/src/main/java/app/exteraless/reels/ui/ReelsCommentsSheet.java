package app.exteraless.reels.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.LinearLayout.LayoutParams;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import app.exteraless.reels.ReelsCommentsController;

/**
 * Шторка комментариев поверх ленты, как в Reels: ролик под ней продолжает играть, список
 * занимает нижние три четверти экрана, внизу поле ввода.
 *
 * <p>Это не отдельный экран, а слой внутри «Клипов». Отдельным фрагментом он был бы
 * переходом: ролик пропал бы, а назад пришлось бы идти по стеку вкладок. Здесь же
 * закрытие — это просто сдвиг панели вниз.
 *
 * <p>Отступы приходят из {@code ReelsActivity}: у корневого слоя уже стоит свой слушатель
 * инсетов, возвращает их наружу, и до детей они не доходят.
 */
public class ReelsCommentsSheet extends FrameLayout {

    public interface Delegate {
        void onSubmit(String text);

        void onReaction(MessageObject comment);

        void onDismiss();
    }

    /** Какую долю экрана занимает шторка. */
    private static final float HEIGHT_FRACTION = 0.74f;

    private final Delegate delegate;
    private final ReelsCommentsController controller;
    private final ReelsCommentsAdapter adapter;

    private final View scrim;
    private final LinearLayout sheet;
    private final TextView title;
    private final RecyclerListView listView;
    private final LinearLayout emptyView;
    private final ProgressBar progress;
    private final EditText input;
    private final ImageView sendButton;
    private final LinearLayout inputRow;

    private boolean loading = true;
    private boolean showing;
    private boolean dismissed;
    private int bottomInset;
    private int imeInset;

    public ReelsCommentsSheet(Context context, ReelsCommentsController controller, Delegate delegate) {
        super(context);
        this.controller = controller;
        this.delegate = delegate;

        scrim = new View(context);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> hide());
        addView(scrim, new LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        sheet = new LinearLayout(context);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFA151517);
        background.setCornerRadii(new float[]{AndroidUtilities.dp(16), AndroidUtilities.dp(16), 0, 0, 0, 0, 0, 0});
        sheet.setBackground(background);
        addView(sheet, new LayoutParams(LayoutHelper.MATCH_PARENT, 0, Gravity.BOTTOM));

        FrameLayout header = new FrameLayout(context);
        title = new TextView(context);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ImageView close = new ImageView(context);
        close.setImageResource(R.drawable.baseline_close_24);
        close.setColorFilter(0x99FFFFFF);
        close.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        close.setContentDescription(LocaleController.getString(R.string.ReelsCloseComments));
        close.setOnClickListener(v -> hide());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(AndroidUtilities.dp(40), AndroidUtilities.dp(40), Gravity.END | Gravity.CENTER_VERTICAL);
        closeParams.rightMargin = AndroidUtilities.dp(6);
        header.addView(close, closeParams);
        sheet.addView(header, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(48)));

        FrameLayout listContainer = new FrameLayout(context);
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, RecyclerView.VERTICAL, false));
        listView.setOverScrollMode(RecyclerListView.OVER_SCROLL_NEVER);
        listView.setVerticalScrollBarEnabled(false);
        adapter = new ReelsCommentsAdapter(comment -> delegate.onReaction(comment), controller);
        listView.setAdapter(adapter);
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView view, int dx, int dy) {
                if (dy >= 0 || listView.getChildCount() == 0 || listView.canScrollVertically(-1)) {
                    return;
                }
                // Дошли до начала ветки — подгружаем более старые комментарии.
                controller.loadMore();
            }
        });
        listContainer.addView(listView, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(GONE);
        TextView emptyText = new TextView(context);
        emptyText.setTextColor(0x99FFFFFF);
        emptyText.setTextSize(15);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setText(R.string.ReelsNoComments);
        emptyView.addView(emptyText, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        listContainer.addView(emptyView, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        progress = new ProgressBar(context);
        progress.setIndeterminate(true);
        listContainer.addView(progress, new FrameLayout.LayoutParams(AndroidUtilities.dp(32), AndroidUtilities.dp(32), Gravity.CENTER));
        sheet.addView(listContainer, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1));

        inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6), AndroidUtilities.dp(12), AndroidUtilities.dp(6));
        sheet.addView(inputRow, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        input = new EditText(context);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0x66FFFFFF);
        input.setTextSize(15);
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(14), AndroidUtilities.dp(8));
        input.setBackground(roundBackground(0x1FFFFFFF, AndroidUtilities.dp(20)));
        input.setHint(R.string.ReelsCommentHint);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                setSendEnabled(s != null && s.toString().trim().length() > 0);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                submit();
                return true;
            }
            return false;
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1));

        sendButton = new ImageView(context);
        sendButton.setImageResource(R.drawable.baseline_send_24);
        sendButton.setColorFilter(Color.WHITE);
        sendButton.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        sendButton.setBackground(circleBackground(0xFF3D7EFF));
        sendButton.setContentDescription(LocaleController.getString(R.string.ReelsSend));
        sendButton.setOnClickListener(v -> submit());
        inputRow.addView(sendButton, new LinearLayout.LayoutParams(AndroidUtilities.dp(38), AndroidUtilities.dp(38)));
        setSendEnabled(false);

        setVisibility(GONE);
    }

    private static GradientDrawable roundBackground(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static GradientDrawable circleBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private void setSendEnabled(boolean enabled) {
        sendButton.setAlpha(enabled ? 1f : 0.4f);
        sendButton.setEnabled(enabled);
    }

    private void submit() {
        String text = input.getText().toString();
        if (TextUtils.isEmpty(text.trim())) {
            return;
        }
        input.setText(null);
        setSendEnabled(false);
        delegate.onSubmit(text);
    }

    /**
     * Отступы снизу: системная панель с панелью вкладок и клавиатура. Клавиатура
     * перекрывает нижний край шторки, поэтому поле ввода поднимается над ней.
     */
    public void setBottomInsets(int systemBottom, int imeBottom) {
        bottomInset = systemBottom;
        imeInset = imeBottom;
        applyBottomMargin();
    }

    private void applyBottomMargin() {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) inputRow.getLayoutParams();
        int margin = Math.max(bottomInset, imeInset);
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin;
            inputRow.setLayoutParams(params);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        LayoutParams params = (LayoutParams) sheet.getLayoutParams();
        int height = (int) ((bottom - top) * HEIGHT_FRACTION);
        if (params.height != height) {
            params.height = height;
            sheet.setLayoutParams(params);
        }
    }

    /** Показывает шторку. Ролик под ней продолжает играть, его глушит вызывающий. */
    public void show(ReactionsLayoutInBubble.VisibleReaction reaction, int totalCount) {
        if (showing || dismissed) {
            return;
        }
        showing = true;
        setTitle(totalCount);
        // Нет у канала избранной реакции — кнопки реакции у комментариев тоже нет.
        adapter.setReaction(reaction != null && reaction.emojicon != null ? reaction.emojicon : "");
        progress.setVisibility(VISIBLE);
        emptyView.setVisibility(GONE);
        inputRow.setVisibility(GONE);
        applyBottomMargin();
        setVisibility(VISIBLE);
        post(() -> {
            if (sheet.getHeight() <= 0) {
                return;
            }
            sheet.setTranslationY(sheet.getHeight());
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(180).start();
            sheet.animate().translationY(0f).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
    }

    private void setTitle(int totalCount) {
        title.setText(LocaleController.formatPluralString("ReelsCommentCount", totalCount));
    }

    /** Закрывает шторку. Сама снимает себя с экрана и сообщает вызывающему. */
    public void hide() {
        if (!showing || dismissed) {
            return;
        }
        dismissed = true;
        showing = false;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
        input.clearFocus();
        int height = sheet.getHeight();
        scrim.animate().alpha(0f).setDuration(180).start();
        sheet.animate().cancel();
        sheet.animate().translationY(height).setDuration(180)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    setVisibility(GONE);
                    delegate.onDismiss();
                }).start();
    }

    /** Ветка найдена: заголовок со счётчиком, поле ввода и список. */
    public void onThreadReady(int totalCount) {
        loading = false;
        progress.setVisibility(GONE);
        setTitle(totalCount);
        // Писать можно не везде: в закрытой группе обсуждений поля нет.
        inputRow.setVisibility(controller.canSend() ? VISIBLE : GONE);
        applyBottomMargin();
        refresh(true);
    }

    /**
     * Список изменился. Прокрутка вниз нужна только когда комментарии добавились в конец
     * или загрузилась первая страница: при подгрузке старых она сбросила бы позицию.
     */
    public void refresh(boolean scrollToEnd) {
        adapter.notifyDataSetChanged();
        boolean empty = controller.getComments().isEmpty();
        emptyView.setVisibility(!loading && empty ? VISIBLE : GONE);
        if (!empty && scrollToEnd) {
            listView.scrollToPosition(controller.getComments().size() - 1);
        }
    }

    /** Ветка не нашлась: закрываем шторку, показывать нечего. */
    public void fail() {
        if (dismissed) {
            return;
        }
        dismissed = true;
        showing = false;
        setVisibility(GONE);
        delegate.onDismiss();
    }

    public boolean isShowing() {
        return showing;
    }
}
