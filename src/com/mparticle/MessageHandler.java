package com.mparticle;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.mparticle.Constants.MessageKey;
import com.mparticle.Constants.MessageType;
import com.mparticle.Constants.Status;
import com.mparticle.MessageDatabase.MessageTable;
import com.mparticle.MessageDatabase.SessionTable;

/* package-private */ final class MessageHandler extends Handler {

    private static final String TAG = Constants.LOG_TAG;

    private final MessageDatabase mDB;
    private final String mApiKey;

    public static final int STORE_MESSAGE = 0;
    public static final int UPDATE_SESSION_ATTRIBUTES = 1;
    public static final int UPDATE_SESSION_END = 2;
    public static final int CREATE_SESSION_END_MESSAGE = 3;
    public static final int END_ORPHAN_SESSIONS = 4;

    public MessageHandler(Context appContext, Looper looper, String apiKey) {
        super(looper);
        mDB = new MessageDatabase(appContext);
        mApiKey = apiKey;
    }

    @Override
    public void handleMessage(Message msg) {
        super.handleMessage(msg);

        switch (msg.what) {
        case STORE_MESSAGE:
            try {
                JSONObject message = (JSONObject) msg.obj;
                String messageType = message.getString(MessageKey.TYPE);
                SQLiteDatabase db = mDB.getWritableDatabase();
                // handle the special case of session-start by creating the
                // session record first
                if (MessageType.SESSION_START == messageType) {
                    dbInsertSession(db, message);
                }
                dbInsertMessage(db, message);

                if (MessageType.SESSION_START != messageType) {
                    dbUpdateSessionEndTime(db, getMessageSessionId(message), message.getLong(MessageKey.TIMESTAMP), 0);
                }
            } catch (SQLiteException e) {
                Log.e(TAG, "Error saving event to mParticle DB", e);
            } catch (JSONException e) {
                Log.e(TAG, "Error with JSON object", e);
            } finally {
                mDB.close();
            }
            break;
        case UPDATE_SESSION_ATTRIBUTES:
            try {
                JSONObject sessionAttributes = (JSONObject) msg.obj;
                String sessionId = sessionAttributes.getString(MessageKey.SESSION_ID);
                String attributes = sessionAttributes.getString(MessageKey.ATTRIBUTES);
                SQLiteDatabase db = mDB.getWritableDatabase();
                dbUpdateSessionAttributes(db, sessionId, attributes);
            } catch (SQLiteException e) {
                Log.e(TAG, "Error updating session attributes in mParticle DB", e);
            } catch (JSONException e) {
                Log.e(TAG, "Error with JSON object", e);
            } finally {
                mDB.close();
            }
            break;
        case UPDATE_SESSION_END:
            try {
                JSONObject sessionTiming = (JSONObject) msg.obj;
                String sessionId = sessionTiming.getString(MessageKey.SESSION_ID);
                long time = sessionTiming.getLong(MessageKey.TIMESTAMP);
                long sessionLength = sessionTiming.getLong(MessageKey.SESSION_LENGTH);
                SQLiteDatabase db = mDB.getWritableDatabase();
                dbUpdateSessionEndTime(db, sessionId, time, sessionLength);
            } catch (SQLiteException e) {
                Log.e(TAG, "Error updating session end time in mParticle DB", e);
            } catch (JSONException e) {
                Log.e(TAG, "Error with JSON object", e);
            } finally {
                mDB.close();
            }
            break;
        case CREATE_SESSION_END_MESSAGE:
            try {
                String sessionId = (String) msg.obj;
                SQLiteDatabase db = mDB.getWritableDatabase();
                String[] selectionArgs = new String[] { sessionId };
                String[] sessionColumns = new String[] { SessionTable.START_TIME, SessionTable.END_TIME,
                        SessionTable.SESSION_LENGTH, SessionTable.ATTRIBUTES };
                Cursor selectCursor = db.query(SessionTable.TABLE_NAME, sessionColumns, SessionTable.SESSION_ID + "=?",
                        selectionArgs, null, null, null);
                selectCursor.moveToFirst();
                long start = selectCursor.getLong(0);
                long end = selectCursor.getLong(1);
                long length = selectCursor.getLong(2);
                String attributes = selectCursor.getString(3);
                JSONObject sessionAttributes = null;
                if (null != attributes) {
                    sessionAttributes = new JSONObject(attributes);
                }

                // create a session-end message
                JSONObject endMessage = MessageManager.createMessageSessionEnd(sessionId, start, end, length,
                        sessionAttributes);

                // insert the record into messages with duration
                dbInsertMessage(db, endMessage);

                // mark session messages ready for BATCH mode upload
                dbUpdateMessageStatus(db, sessionId, Status.BATCH_READY);

                // delete the processed session record
                db.delete(SessionTable.TABLE_NAME, SessionTable.SESSION_ID + "=?", new String[] { sessionId });

            } catch (SQLiteException e) {
                Log.e(TAG, "Error creating session end message in mParticle DB", e);
            } catch (JSONException e) {
                Log.e(TAG, "Error with JSON object", e);
            } finally {
                mDB.close();
            }
            break;
        case END_ORPHAN_SESSIONS:
            try {
                // find left-over sessions that exist during startup and end them
                SQLiteDatabase db = mDB.getWritableDatabase();
                String[] selectionArgs = new String[] { mApiKey };
                String[] sessionColumns = new String[] { SessionTable.SESSION_ID };
                Cursor selectCursor = db.query(SessionTable.TABLE_NAME, sessionColumns,
                        SessionTable.API_KEY + "=?",
                        selectionArgs, null, null, null);
                // NOTE: there should be at most one orphan per api key - but
                // process any that are found
                while (selectCursor.moveToNext()) {
                    String sessionId = selectCursor.getString(0);
                    sendMessage(obtainMessage(MessageHandler.CREATE_SESSION_END_MESSAGE, sessionId));
                }
            } catch (SQLiteException e) {
                Log.e(TAG, "Error processing initialization in mParticle DB", e);
            } finally {
                mDB.close();
            }
            break;
        }
    }

    private void dbInsertSession(SQLiteDatabase db, JSONObject message) throws JSONException {
        ContentValues contentValues = new ContentValues();
        contentValues.put(SessionTable.API_KEY, mApiKey);
        contentValues.put(SessionTable.SESSION_ID, message.getString(MessageKey.ID));
        contentValues.put(SessionTable.START_TIME, message.getLong(MessageKey.TIMESTAMP));
        contentValues.put(SessionTable.END_TIME, message.getLong(MessageKey.TIMESTAMP));
        contentValues.put(SessionTable.SESSION_LENGTH, 0);
        db.insert(SessionTable.TABLE_NAME, null, contentValues);
    }

    private void dbInsertMessage(SQLiteDatabase db, JSONObject message) throws JSONException {
        ContentValues contentValues = new ContentValues();
        contentValues.put(MessageTable.API_KEY, mApiKey);
        contentValues.put(MessageTable.CREATED_AT, message.getLong(MessageKey.TIMESTAMP));
        contentValues.put(MessageTable.SESSION_ID, getMessageSessionId(message));
        contentValues.put(MessageTable.MESSAGE, message.toString());
        contentValues.put(MessageTable.STATUS, Status.READY);
        db.insert(MessageTable.TABLE_NAME, null, contentValues);
    }

    private void dbUpdateMessageStatus(SQLiteDatabase db, String sessionId, long status) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(MessageTable.STATUS, status);
        String[] whereArgs = { sessionId };
        db.update(MessageTable.TABLE_NAME, contentValues, MessageTable.SESSION_ID + "=?", whereArgs);
    }

    private void dbUpdateSessionAttributes(SQLiteDatabase db, String sessionId, String attributes) {
        ContentValues sessionValues = new ContentValues();
        sessionValues.put(SessionTable.ATTRIBUTES, attributes);
        String[] whereArgs = { sessionId };
        db.update(SessionTable.TABLE_NAME, sessionValues, SessionTable.SESSION_ID + "=?", whereArgs);
    }

    private void dbUpdateSessionEndTime(SQLiteDatabase db, String sessionId, long endTime, long sessionLength) {
        ContentValues sessionValues = new ContentValues();
        sessionValues.put(SessionTable.END_TIME, endTime);
        if (sessionLength > 0) {
            sessionValues.put(SessionTable.SESSION_LENGTH, sessionLength);
        }
        String[] whereArgs = { sessionId };
        db.update(SessionTable.TABLE_NAME, sessionValues, SessionTable.SESSION_ID + "=?", whereArgs);
    }

    // helper method for getting a session id out of a message since
    // session-start messages use the id field
    private String getMessageSessionId(JSONObject message) throws JSONException {
        String sessionId;
        if (MessageType.SESSION_START == message.getString(MessageKey.TYPE)) {
            sessionId = message.getString(MessageKey.ID);
        } else {
            sessionId = message.optString(MessageKey.SESSION_ID, "NO-SESSION");
        }
        return sessionId;
    }
}
