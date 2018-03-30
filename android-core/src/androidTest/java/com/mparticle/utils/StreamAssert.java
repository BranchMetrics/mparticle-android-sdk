package com.mparticle.utils;

import android.os.Handler;
import android.os.Looper;

import junit.framework.AssertionFailedError;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static junit.framework.Assert.fail;

public class StreamAssert<T> {
    private List<Assert<T>> assertions;
    private List<T> collection = new ArrayList<T>();
    private boolean strict = false;
    private Handler handler;

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public void startTimer(long millis, boolean block) {
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        check(true);
                    }
                }, millis);
        if (block) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private StreamAssert() {
        assertions = new ArrayList<Assert<T>>();
    }

    public static <T> StreamAssert<T> first(Assert<T> assertion) {
        StreamAssert expected = new StreamAssert();
        expected.assertions.add(0, assertion);
        return expected;
    }

    public StreamAssert then(Assert<T> assertion) {
        assertions.add(assertion);
        return this;
    }

    public void collect(T t) {
        collection.add(t);
        check();
    }


    /**
     * returns a boolean based on whether or not all the assertions have been met by the collection
     *
     * @return
     */
    private boolean check() {
        return check(false);
    }

    private boolean check(boolean throwException) {
        if (strict && collection.size() > assertions.size()) {
            if (throwException) {
                fail("too many items collected");
            }
            return false;
        }
        int index = 0;
        List<Assert<T>> assertionsCopy = new ArrayList<Assert<T>>(assertions);
        List<T> collectionCopy = new ArrayList<T>(collection);
        for (Assert<T> tAssert : assertionsCopy) {
            boolean passed;
            do {
                if (index >= collectionCopy.size() && assertions.size() > 0) {
                    ///ran out of collected items to test against assertion
                    if (throwException) {
                        fail("timed out, object not found to meet Stream Assertions");
                    }
                    return false;
                }

                // We have to test both conditions in the Assert interface. The idea is, either on
                // of them should be implemented. passing is either
                // - assertTrueI returning true OR
                // - assertObject not throwing a runtime exception
                // we are going to be eating a lot of runtime exceptions in this code.. just eat it
                // and count it as a not pass.. on the last run, when "throwException" is true,
                // we will let the exception go, so the user will know where the real condition failed
                passed = false;
                T item = collectionCopy.get(index++);
                try {
                    tAssert.assertObject(item);
                    passed = tAssert.assertTrueI(item);
                } catch (AssertionFailedError ex) {
                    if (throwException) {
                        throw ex;
                    }
                    passed = false;
                } catch (JSONException jse) {
                    passed = false;
                } catch (Exception ex) {
                    if (throwException) {
                        ex.printStackTrace();
                        fail(ex.getMessage());
                    }
                    passed = false;
                }
                if (passed) {
                    assertions.remove(tAssert);
                    collection.remove(item);
                    index--;
                }
            }
            while (!passed);
        }
        if (collection.size() < assertions.size()) {
            if (throwException) {
                fail("timed out, object not found to meet Stream Assertions");
            }
            return false;
        }
        return true;
    }

    interface Assert<T> {
        boolean assertTrueI(T object) throws Exception;

        void assertObject(T object) throws Exception;
    }
}