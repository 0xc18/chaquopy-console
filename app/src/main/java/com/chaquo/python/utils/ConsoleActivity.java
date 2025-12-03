package com.chaquo.python.utils;

import androidx.annotation.NonNull;
import android.app.*;
import android.graphics.*;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.*;
import androidx.core.content.*;
import androidx.lifecycle.*;

public abstract class ConsoleActivity extends BaseActivity
        implements ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnScrollChangedListener {

    private final int MAX_SCROLLBACK_LEN = 100000;

    private EditText etInput;
    private ScrollView svOutput;
    private TextView tvOutput;
    private int outputWidth = -1, outputHeight = -1;

    enum Scroll {
        TOP, BOTTOM
    }
    private Scroll scrollRequest;

    public static class ConsoleModel extends ViewModel {
        boolean pendingNewline = false;
        int scrollChar = 0;
        int scrollAdjust = 0;
    }
    private ConsoleModel consoleModel;

    protected Task task;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        consoleModel = ViewModelProviders.of(this).get(ConsoleModel.class);
        task = ViewModelProviders.of(this).get(getTaskClass());
        setContentView(resId("layout", "activity_console"));
        createInput();
        createOutput();
    }

    protected abstract Class<? extends Task> getTaskClass();

    private void createInput() {
        etInput = findViewById(resId("id", "etInput"));

        etInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable e) {
                for (CharacterStyle cs : e.getSpans(0, e.length(), CharacterStyle.class)) {
                    e.removeSpan(cs);
                }
            }
        });

        etInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if ((actionId == EditorInfo.IME_ACTION_DONE && event == null) ||
                        (event != null && event.getAction() == KeyEvent.ACTION_UP)
                ) {
                    String text = etInput.getText().toString() + "\n";
                    etInput.setText("");
                    output(span(text, new StyleSpan(Typeface.BOLD)));
                    scrollTo(Scroll.BOTTOM);
                    task.onInput(text);
                }
                return true;
            }
        });

        task.inputEnabled.observe(this, new Observer<Boolean>() {
            @Override public void onChanged(@Nullable Boolean enabled) {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (enabled) {
                    etInput.setVisibility(View.VISIBLE);
                    etInput.setEnabled(true);
                    etInput.requestFocus();
                    imm.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT);
                } else {
                    etInput.setEnabled(false);
                    imm.hideSoftInputFromWindow(tvOutput.getWindowToken(), 0);
                }
            }
        });
    }

    private void createOutput() {
        svOutput = findViewById(resId("id", "svOutput"));
        svOutput.getViewTreeObserver().addOnGlobalLayoutListener(this);

        tvOutput = findViewById(resId("id", "tvOutput"));
        tvOutput.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
    }

    @Override protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        if (task.getState() != Thread.State.NEW) {
            super.onRestoreInstanceState(savedInstanceState);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (task.getState() == Thread.State.NEW) {
            task.start();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        saveScroll();
    }

    @Override public void onGlobalLayout() {
        if (outputWidth != svOutput.getWidth() || outputHeight != svOutput.getHeight()) {
            if (outputWidth == -1) {
                svOutput.getViewTreeObserver().addOnScrollChangedListener(this);
            }
            outputWidth = svOutput.getWidth();
            outputHeight = svOutput.getHeight();
            restoreScroll();
        } else if (scrollRequest != null) {
            int y = -1;
            switch (scrollRequest) {
                case TOP: y = 0; break;
                case BOTTOM: y = tvOutput.getHeight(); break;
            }
            svOutput.scrollTo(0, y);
            scrollRequest = null;
        }
    }

    @Override public void onScrollChanged() {
        saveScroll();
    }

    private void saveScroll() {
        if (isScrolledToBottom()) {
            consoleModel.scrollChar = tvOutput.getText().length();
            consoleModel.scrollAdjust = 0;
        } else {
            int scrollY = svOutput.getScrollY();
            Layout layout = tvOutput.getLayout();
            if (layout != null) {
                int line = layout.getLineForVertical(scrollY);
                consoleModel.scrollChar = layout.getLineStart(line);
                consoleModel.scrollAdjust = scrollY - layout.getLineTop(line);
            }
        }
    }

    private void restoreScroll() {
        removeCursor();
        Layout layout = tvOutput.getLayout();
        if (layout != null) {
            int line = layout.getLineForOffset(consoleModel.scrollChar);
            svOutput.scrollTo(0, layout.getLineTop(line) + consoleModel.scrollAdjust);
        }
        saveScroll();

        task.output.removeObservers(this);
        task.output.observe(this, new Observer<CharSequence>() {
            @Override public void onChanged(@Nullable CharSequence text) {
                output(text);
            }
        });
    }

    private boolean isScrolledToBottom() {
        int visibleHeight = (svOutput.getHeight() - svOutput.getPaddingTop() -
                svOutput.getPaddingBottom());
        int maxScroll = Math.max(0, tvOutput.getHeight() - visibleHeight);
        return (svOutput.getScrollY() >= maxScroll);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater mi = getMenuInflater();
        mi.inflate(resId("menu", "top_bottom"), menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == resId("id", "menu_top")) {
            scrollTo(Scroll.TOP);
        } else if (id == resId("id", "menu_bottom")) {
            scrollTo(Scroll.BOTTOM);
        } else {
            return false;
        }
        return true;
    }

    public static Spannable span(CharSequence text, Object... spans) {
        Spannable spanText = new SpannableStringBuilder(text);
        for (Object span : spans) {
            spanText.setSpan(span, 0, text.length(), 0);
        }
        return spanText;
    }

    // =========================================================================
    //                           SIMPLIFIED output()
    // =========================================================================
    private void output(CharSequence text) {
        removeCursor();

        // Always replace entire tvOutput
        tvOutput.setText(text);

        // Reset newline logic
        consoleModel.pendingNewline = false;

        scrollTo(Scroll.BOTTOM);
    }

    private void scrollTo(Scroll request) {
        if (scrollRequest != Scroll.TOP) {
            scrollRequest = request;
            svOutput.requestLayout();
        }
    }

    private void removeCursor() {
        Spannable text = (Spannable) tvOutput.getText();
        int selStart = Selection.getSelectionStart(text);
        int selEnd = Selection.getSelectionEnd(text);

        if (!(text instanceof Editable)) {
            tvOutput.setText(text, TextView.BufferType.EDITABLE);
            text = (Editable) tvOutput.getText();

            if (selStart >= 0) {
                Selection.setSelection(text, selStart, selEnd);
            }
        }

        if (selStart >= 0 && selStart == selEnd) {
            Selection.removeSelection(text);
        }
    }

    public int resId(String type, String name) {
        return Utils.resId(this, type, name);
    }

    // =========================================================================

    public static abstract class Task extends AndroidViewModel {

        private Thread.State state = Thread.State.NEW;

        public void start() {
            new Thread(() -> {
                try {
                    Task.this.run();
                    output(spanColor("[Finished]", resId("color", "console_meta")));
                } finally {
                    inputEnabled.postValue(false);
                    state = Thread.State.TERMINATED;
                }
            }).start();
            state = Thread.State.RUNNABLE;
        }

        public Thread.State getState() { return state; }

        public MutableLiveData<Boolean> inputEnabled = new MutableLiveData<>();
        public BufferedLiveEvent<CharSequence> output = new BufferedLiveEvent<>();

        public Task(Application app) {
            super(app);
            inputEnabled.setValue(false);
        }

        public abstract void run();

        public void onInput(String text) {}

        public void output(final CharSequence text) {
            if (text.length() == 0) return;
            output.postValue(text);
        }

        public void outputError(CharSequence text) {
            output(spanColor(text, resId("color", "console_error")));
        }

        public Spannable spanColor(CharSequence text, int colorId) {
            int color = ContextCompat.getColor(this.getApplication(), colorId);
            return span(text, new ForegroundColorSpan(color));
        }

        public int resId(String type, String name) {
            return Utils.resId(getApplication(), type, name);
        }
    }
}
