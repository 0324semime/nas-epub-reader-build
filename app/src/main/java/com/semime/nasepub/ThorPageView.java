package com.semime.nasepub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * One physical page. Japanese spread mode flows top-to-bottom, right-to-left.
 * The fit() and draw() functions share exactly the same stepper, so the second
 * display begins at the first page's next UTF-16 text offset without overlap.
 *
 * Text-only EPUB pilot: ruby, images and professional Japanese kinsoku/ruby
 * remain future enhancements. This view never enables script/network access.
 */
public final class ThorPageView extends View {
    private final TextPaint ink = new TextPaint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String content="";
    private int fontSp;
    private boolean vertical;
    private boolean rightPage;
    private int foreground=Color.rgb(42,51,43);
    private int background=Color.rgb(249,247,239);
    private int accent=Color.rgb(210,196,161);

    public ThorPageView(Context context,int size,boolean vertical,boolean rightPage){
        super(context);
        fontSp=size;
        this.vertical=vertical;
        this.rightPage=rightPage;
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        setFocusable(false);
        updatePaint();
        setWillNotDraw(false);
    }
    public ThorPageView(Context context,AttributeSet attrs){
        this(context,18,true,false);
    }
    private float pixels(float dp){
        return dp*getResources().getDisplayMetrics().density;
    }
    private void updatePaint(){
        ink.setTypeface(Typeface.create("serif",Typeface.NORMAL));
        ink.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
            fontSp,getResources().getDisplayMetrics()));
        ink.setColor(foreground);
        ink.setAntiAlias(true);
    }
    public void configure(int size,boolean vertical,boolean rightPage,
                          int textColor,int paperColor,int borderColor){
        this.fontSp=size;
        this.vertical=vertical;
        this.rightPage=rightPage;
        this.foreground=textColor;
        this.background=paperColor;
        this.accent=borderColor;
        updatePaint();
        invalidate();
    }
    public void setPage(String value){
        content=value==null?"":value;
        invalidate();
    }
    // The smaller lower/LEFT display dedicates almost the whole surface to text.
    // The upper/RIGHT page keeps comfortable book-like margins.
    private int padSide(){return Math.round(pixels(rightPage?20:7));}
    private int padTop(){return Math.round(pixels(rightPage?20:7));}
    private int contentWidth(){return Math.max(0,getWidth()-padSide()*2);}
    private int contentHeight(){return Math.max(0,getHeight()-padTop()*2);}
    private float rowStep(){return ink.getTextSize()*1.48f;}
    private float columnStep(){return ink.getTextSize()*1.65f;}
    private static int safeCodepoint(String text,int index){
        char a=text.charAt(index);
        if(Character.isHighSurrogate(a)&&index+1<text.length()
            &&Character.isLowSurrogate(text.charAt(index+1)))
            return Character.toCodePoint(a,text.charAt(index+1));
        return a;
    }
    private static boolean isCombining(int cp){
        int type=Character.getType(cp);
        return type==Character.NON_SPACING_MARK||
            type==Character.COMBINING_SPACING_MARK||
            type==Character.ENCLOSING_MARK;
    }
    private interface GlyphCallback {
        void accept(String glyph,int col,int row);
    }
    /**
     * Returns the number of UTF-16 code units consumed. Newlines and excess
     * spaces are deliberately consumed, never displayed as squares.
     */
    private int scanVertical(String input,GlyphCallback callback){
        if(input==null||input.isEmpty())return 0;
        int columns=(int)Math.floor(contentWidth()/columnStep());
        int rows=(int)Math.floor(contentHeight()/rowStep());
        if(columns<1||rows<1)return 0;
        int col=0,row=0,index=0;
        while(index<input.length()&&col<columns){
            int cp=safeCodepoint(input,index);
            int units=Character.charCount(cp);
            if(cp=='\r'){index+=units;continue;}
            if(cp=='\n'){
                index+=units;
                // A small paragraph gap, without wasting a full new column.
                row+=row==0?1:2;
                if(row>=rows){row=0;col++;}
                continue;
            }
            if(cp=='\t')cp=' ';
            if(row>=rows){row=0;col++;}
            if(col>=columns)break;
            if(cp==' '&&row==0){index+=units;continue;}
            String glyph=new String(Character.toChars(cp));
            int trailing=index+units;
            while(trailing<input.length()){
                int extra=safeCodepoint(input,trailing);
                if(!isCombining(extra))break;
                glyph+=new String(Character.toChars(extra));
                trailing+=Character.charCount(extra);
            }
            if(callback!=null)callback.accept(glyph,col,row);
            row++;index=trailing;
        }
        return index;
    }
    private StaticLayout horizontalLayout(String source){
        return StaticLayout.Builder.obtain(source,0,source.length(),
                ink,Math.max(1,contentWidth()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setLineSpacing(0,1.28f)
            .build();
    }
    public int fit(String source){
        if(source==null||source.isEmpty())return 0;
        if(contentWidth()<45||contentHeight()<60)return 0;
        // Large source chapters are progressively paginated in short slices.
        final int maxTrial=Math.min(source.length(),7000);
        String trial=source.substring(0,maxTrial);
        if(vertical)return scanVertical(trial,null);
        StaticLayout layout=horizontalLayout(trial);
        int end=0;
        for(int i=0;i<layout.getLineCount();i++){
            if(layout.getLineBottom(i)>contentHeight())break;
            end=layout.getLineEnd(i);
        }
        if(end==0 && !trial.isEmpty())end=Character.charCount(trial.codePointAt(0));
        if(end>0&&end<trial.length()
            &&Character.isHighSurrogate(trial.charAt(end-1))
            &&Character.isLowSurrogate(trial.charAt(end)))end--;
        return Math.max(0,end);
    }
    private void drawVerticalGlyph(Canvas canvas,String glyph,int col,int row){
        final float x=getWidth()-padSide()-(col+0.52f)*columnStep();
        final float y=padTop()+(row+0.83f)*rowStep();
        int cp=glyph.codePointAt(0);
        // Japanese vertical writing uses the upright ideograph; Latin and
        // typographic horizontal marks rotate clockwise as approximations.
        boolean rotate=(cp<128&&Character.isLetterOrDigit(cp))
            ||"ー－―‐〜～（）()[]{}〈〉《》「」『』【】＝…—".indexOf(cp)>=0;
        if(rotate){
            canvas.save();
            canvas.rotate(90,x,y-ink.getTextSize()*0.42f);
            canvas.drawText(glyph,x,y,ink);
            canvas.restore();
        }else{
            float center=ink.measureText(glyph)*0.5f;
            canvas.drawText(glyph,x-center,y,ink);
        }
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        canvas.drawColor(background);
        // Subtle page edge on the inner seam; right page seam is at its left,
        // left page seam is at its right.
        edge.setStrokeWidth(Math.max(1,pixels(1)));
        edge.setColor(accent);
        float x=rightPage?pixels(6):getWidth()-pixels(6);
        canvas.drawLine(x,pixels(10),x,getHeight()-pixels(10),edge);
        if(content.isEmpty())return;
        canvas.save();
        canvas.clipRect(padSide(),padTop(),
            getWidth()-padSide(),getHeight()-padTop());
        if(vertical){
            scanVertical(content,(glyph,col,row)->drawVerticalGlyph(canvas,glyph,col,row));
        }else{
            canvas.translate(padSide(),padTop());
            horizontalLayout(content).draw(canvas);
        }
        canvas.restore();
    }
}
