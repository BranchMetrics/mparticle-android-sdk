package com.mparticle.integration_tests;

import com.mparticle.AccessUtils;
import com.mparticle.BaseCleanStartedEachTest;
import com.mparticle.ConsentEvent;
import com.mparticle.MParticle;
import com.mparticle.mock.utils.RandomUtils;
import com.mparticle.utils.MParticleUtils;

import org.junit.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static junit.framework.Assert.assertTrue;

public class MParticleApiOutgoingTest extends BaseCleanStartedEachTest {

    @Test
    public void testConfigFetch() throws Exception {
        MParticle.getInstance().upload();
        mServer.waitForVerify(getRequestedFor(urlPathMatching("/v([0-9]*)/([0-9a-zA-Z]*)/config")), 5000);
    }

    @Test
    public void testEventUpload() throws Exception {
        MParticle.getInstance().logEvent(MParticleUtils.getInstance().getRandomMPEventSimple());
        MParticle.getInstance().upload();
        mServer.waitForVerify(postRequestedFor(urlPathMatching("/v([0-9]*)/([0-9a-zA-Z]*)/events")), 10000);
    }

    @Test
    public void testConsentUpload() throws Exception {
        AccessUtils.logEvent(RandomUtils.getInstance().getRandomMPConsentEvent());
        MParticle.getInstance().upload();
        mServer.waitForVerify(postRequestedFor(urlPathMatching("/v([0-9]*)/([0-9a-zA-Z]*)/events")), 20000);
    }

    @Override
    protected void beforeClass() throws Exception {

    }

    @Override
    protected void before() throws Exception {

    }
}
