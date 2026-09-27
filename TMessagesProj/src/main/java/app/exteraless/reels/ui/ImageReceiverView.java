package app.exteraless.reels.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import org.telegram.messenger.ImageReceiver;

/**
 * Полотно для {@link ImageReceiver}: постер ролика рисуется самим получателем, а не
 * {@code ImageView}, потому что у анимированных GIF свой жизненный цикл и своя очередь
 * анимации.
 *
 * <p>Отдельно считаем прямоугольник под изображение: {@code ImageReceiver} либо
 * растягивает картинку на заданный прямоугольник, либо вписывает её целиком, а ленте
 * нужно ни то, ни другое. Ролики кадрируются по экрану (как в Reels), фотографии
 * вписываются целиком — иначе у мема 4:3 срезали бы половину текста. Прямоугольник
 * вычисляется по пропорциям медиа, а лишнее обрезается клипом.
 */
public class ImageReceiverView extends View {

    private final ImageReceiver imageReceiver = new ImageReceiver(this);

    private float mediaAspect;
    private boolean cover;

    public ImageReceiverView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    public ImageReceiver getImageReceiver() {
        return imageReceiver;
    }

    /**
     * Пропорции медиа и способ вписать его в страницу. aspect &lt;= 0 — пропорции
     * неизвестны, тогда картинка растянется на всю страницу.
     */
    public void setMediaAspect(float aspect, boolean cover) {
        this.mediaAspect = aspect;
        this.cover = cover;
        updateImageCoords();
        invalidate();
    }

    public void cancelLoading() {
        imageReceiver.cancelLoadImage();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateImageCoords();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        imageReceiver.draw(canvas);
        canvas.restore();
    }

    private void updateImageCoords() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || mediaAspect <= 0) {
            imageReceiver.setImageCoords(0, 0, width, height);
            return;
        }
        float viewAspect = width / (float) height;
        float imageWidth;
        float imageHeight;
        // cover — провести по большей стороне, fit — по меньшей.
        boolean useWidth = cover ? mediaAspect < viewAspect : mediaAspect > viewAspect;
        if (useWidth) {
            imageWidth = width;
            imageHeight = width / mediaAspect;
        } else {
            imageHeight = height;
            imageWidth = height * mediaAspect;
        }
        imageReceiver.setImageCoords((width - imageWidth) / 2f, (height - imageHeight) / 2f, imageWidth, imageHeight);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        imageReceiver.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        imageReceiver.onDetachedFromWindow();
        super.onDetachedFromWindow();
    }
}
