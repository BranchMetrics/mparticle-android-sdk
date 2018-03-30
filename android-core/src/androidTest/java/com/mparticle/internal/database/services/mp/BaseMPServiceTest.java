package com.mparticle.internal.database.services.mp;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.support.annotation.CallSuper;
import android.support.test.InstrumentationRegistry;

import com.mparticle.BaseCleanInstallEachTest;
import com.mparticle.internal.networking.BaseMPMessage;
import com.mparticle.internal.Session;
import com.mparticle.internal.database.BaseDatabase;
import com.mparticle.internal.database.tables.mp.MParticleDatabaseHelper;
import com.mparticle.mock.utils.RandomUtils;

import org.json.JSONException;

import java.util.UUID;

abstract public class BaseMPServiceTest extends BaseCleanInstallEachTest {
    protected static SQLiteDatabase database;

    @CallSuper
    @Override
    protected void before() throws Exception {
        SQLiteOpenHelper openHelper = new BaseDatabase(new MParticleDatabaseHelper(InstrumentationRegistry.getContext()), MParticleDatabaseHelper.DB_NAME);
        database = openHelper.getWritableDatabase();
    }

    @Override
    protected void beforeClass() throws Exception {

    }

    BaseMPMessage getMpMessage() throws JSONException {
        return getMpMessage(UUID.randomUUID().toString());
    }

    BaseMPMessage getMpMessage(String sessionId) throws JSONException {
        return getMpMessage(sessionId, RandomUtils.getInstance().randomLong(Long.MIN_VALUE, Long.MAX_VALUE));
    }

    BaseMPMessage getMpMessage(String sessionId, long mpid) throws JSONException {
        RandomUtils random = RandomUtils.getInstance();
        Session session = new Session();
        session.mSessionID = sessionId;
        return new BaseMPMessage.Builder(random.getAlphaNumericString(random.randomInt(1, 24)), session, new Location(random.getAlphaNumericString(random.randomInt(1, 55))), mpid).build();
    }
}
