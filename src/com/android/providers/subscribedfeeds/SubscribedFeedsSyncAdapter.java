/*
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** See the License for the specific language governing permissions and
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** limitations under the License.
*/

package com.android.providers.subscribedfeeds;

import com.google.android.gdata.client.AndroidGDataClient;
import com.google.android.gdata.client.AndroidXmlParserFactory;
import com.google.android.googlelogin.GoogleLoginServiceBlockingHelper;
import com.google.android.googlelogin.GoogleLoginServiceConstants;
import com.google.android.googlelogin.GoogleLoginServiceNotFoundException;
import com.google.android.providers.AbstractGDataSyncAdapter;
import com.google.wireless.gdata.client.GDataServiceClient;
import com.google.wireless.gdata.client.QueryParams;
import com.google.wireless.gdata.data.Entry;
import com.google.wireless.gdata.data.Feed;
import com.google.wireless.gdata.parser.ParseException;
import com.google.wireless.gdata.subscribedfeeds.client.SubscribedFeedsClient;
import com.google.wireless.gdata.subscribedfeeds.data.FeedUrl;
import com.google.wireless.gdata.subscribedfeeds.data.SubscribedFeedsEntry;
import com.google.wireless.gdata.subscribedfeeds.parser.xml.XmlSubscribedFeedsGDataParserFactory;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SyncContext;
import android.content.SyncResult;
import android.content.SyncStats;
import android.content.SyncableContentProvider;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.SubscribedFeeds;
import android.util.Log;
import android.text.TextUtils;

import java.io.IOException;

/**
 * Implements a SyncAdapter for SubscribedFeeds
 */
public class SubscribedFeedsSyncAdapter extends AbstractGDataSyncAdapter {
    private final SubscribedFeedsClient mSubscribedFeedsClient;

    private final static String FEED_URL = "https://android.clients.google.com/gsync/sub";

    private static final String ROUTINGINFO_PARAMETER = "routinginfo";

    protected SubscribedFeedsSyncAdapter(Context context, SyncableContentProvider provider) {
        super(context, provider);
        mSubscribedFeedsClient =
            new SubscribedFeedsClient(
                    new AndroidGDataClient(context),
                    new XmlSubscribedFeedsGDataParserFactory(
                            new AndroidXmlParserFactory()));
    }

    @Override
    protected GDataServiceClient getGDataServiceClient() {
        return mSubscribedFeedsClient;
    }

    protected boolean handleAllDeletedUnavailable(GDataSyncData syncData, String feed) {
        Log.w(TAG, "subscribed feeds don't use tombstones");

        // this should never happen, but if it does pretend that we are able to handle it.
        return true;
    }

    /*
     * Takes the entry, casts it to a SubscribedFeedsEntry and executes the
     * appropriate actions on the ContentProvider to represent the entry.
     */
    protected void updateProvider(Feed feed, Long syncLocalId,
            Entry baseEntry, ContentProvider provider, Object syncInfo,
            GDataSyncData.FeedData feedSyncData)
            throws ParseException {
        ContentValues values = new ContentValues();
        final Account account = getAccount();
        values.put(SubscribedFeeds.Feeds._SYNC_ACCOUNT, account.name);
        values.put(SubscribedFeeds.Feeds._SYNC_ACCOUNT_TYPE, account.type);
        values.put(SubscribedFeeds.Feeds._SYNC_LOCAL_ID, syncLocalId);
        final SubscribedFeedsEntry entry = (SubscribedFeedsEntry) baseEntry;
        final String id = entry.getId();
        final String editUri = entry.getEditUri();
        String version = null;
        if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(editUri)) {
            final String serverId = id.substring(id.lastIndexOf('/') + 1);
            version = editUri.substring(editUri.lastIndexOf('/') + 1);
            values.put(SubscribedFeeds.Feeds._SYNC_ID, serverId);
            values.put(SubscribedFeeds.Feeds._SYNC_VERSION, version);
        }
        if (baseEntry.isDeleted()) {
            provider.insert(SubscribedFeeds.Feeds.DELETED_CONTENT_URI, values);
        } else {
            values.put(SubscribedFeeds.Feeds.FEED, entry.getSubscribedFeed().getFeed());
            if (!TextUtils.isEmpty(version)) {
                values.put(SubscribedFeeds.Feeds._SYNC_TIME, version);
            }
            values.put(SubscribedFeeds.Feeds._SYNC_DIRTY, "0");
            provider.insert(SubscribedFeeds.Feeds.CONTENT_URI, values);
        }
    }

    @Override
    protected String getFeedUrl(Account account) {
        return FEED_URL;
    }

    protected Class getFeedEntryClass() {
        return SubscribedFeedsEntry.class;
    }

    @Override
    protected void updateQueryParameters(QueryParams params, GDataSyncData.FeedData feedSyncData) {
        params.setParamValue(ROUTINGINFO_PARAMETER, getRoutingInfoForAccount(getAccount()));
    }

    @Override
    protected Entry newEntry() {
        return new SubscribedFeedsEntry();
    }

    /* (non-Javadoc)
    * @see android.content.SyncAdapter#getServerDiffs
    */
    @Override
    public void getServerDiffs(SyncContext context, SyncData syncData,
            SyncableContentProvider tempProvider,
            Bundle extras, Object syncInfo, SyncResult syncResult) {
        tempProvider.setContainsDiffs(false /* the server returns all records, not just diffs */);
        super.getServerDiffs(context, syncData, tempProvider, extras, syncInfo, syncResult);
    }

    public void onAccountsChanged(Account[] accounts) {
        // no need to do anything
    }

    @Override
    protected Cursor getCursorForTable(ContentProvider cp, Class entryClass) {
        if (entryClass != SubscribedFeedsEntry.class) {
            throw new IllegalArgumentException("unexpected entry class, " + entryClass.getName());
        }
        return cp.query(SubscribedFeeds.Feeds.CONTENT_URI, null, null, null, null);
    }

    @Override
    protected Cursor getCursorForDeletedTable(ContentProvider cp, Class entryClass) {
        if (entryClass != SubscribedFeedsEntry.class) {
            throw new IllegalArgumentException("unexpected entry class, " + entryClass.getName());
        }
        return cp.query(SubscribedFeeds.Feeds.DELETED_CONTENT_URI, null, null, null, null);
    }

    @Override
    protected String cursorToEntry(SyncContext context,
            Cursor c, Entry baseEntry, Object syncInfo) throws ParseException {
        final String accountName =
                c.getString(c.getColumnIndex(SubscribedFeeds.Feeds._SYNC_ACCOUNT));
        final String accountType =
                c.getString(c.getColumnIndex(SubscribedFeeds.Feeds._SYNC_ACCOUNT_TYPE));
        final Account account = new Account(accountName, accountType);
        final String service = c.getString(c.getColumnIndex(SubscribedFeeds.Feeds.SERVICE));
        final String id = c.getString(c.getColumnIndex(SubscribedFeeds.Feeds._SYNC_ID));
        final String feed = c.getString(c.getColumnIndex(SubscribedFeeds.Feeds.FEED));

        String authToken;
        try {
            authToken = AccountManager.get(getContext()).blockingGetAuthToken(
                    new Account(accountName, "com.google.GAIA"), service, true);
        } catch (IOException e) {
            Log.e("Sync", "caught exception while attempting to get an " +
                    "authtoken for account " + account +
                    ", service " + service + ": " + e.toString());
            throw new ParseException(e.getMessage(), e);
        } catch (AuthenticatorException e) {
            Log.e("Sync", "caught exception while attempting to get an " +
                    "authtoken for account " + account +
                    ", service " + service + ": " + e.toString());
            throw new ParseException(e.getMessage(), e);
        } catch (OperationCanceledException e) {
            Log.e("Sync", "caught exception while attempting to get an " +
                    "authtoken for account " + account +
                    ", service " + service + ": " + e.toString());
            throw new ParseException(e.getMessage(), e);
        }

        FeedUrl subscribedFeedUrl = new FeedUrl(feed, service, authToken);
        SubscribedFeedsEntry entry = (SubscribedFeedsEntry) baseEntry;
        if (id != null) {
            entry.setId(getFeedUrl(account) + "/" + id);
            entry.setEditUri(entry.getId());
        }
        entry.setRoutingInfo(getRoutingInfoForAccount(account));
        entry.setSubscribedFeed(subscribedFeedUrl);
        entry.setClientToken(subscribedFeedUrl.getFeed());

        String createUrl = null;
        if (id == null) {
            createUrl = getFeedUrl(account);
        }
        return createUrl;
    }

    @Override
    protected void deletedCursorToEntry(SyncContext context, Cursor c, Entry entry) {
        final String id = c.getString(c.getColumnIndexOrThrow(SubscribedFeeds.Feeds._SYNC_ID));
        entry.setId(id);
        entry.setEditUri(getFeedUrl(getAccount()) + "/" + id);
    }

    /**
     * Returns a string that can be used by the GSyncSubscriptionServer to
     * route messages back to this account via GTalkService.
     * TODO: this should eventually move into a general place so that others
     * can use it.
     *
     * @param account the account whose routing info we want
     * @return the routing info for this account
     */
    public String getRoutingInfoForAccount(Account account) {
        Context context = getContext();
        long androidId;
        try {
            androidId = GoogleLoginServiceBlockingHelper.getAndroidId(context);
        } catch (GoogleLoginServiceNotFoundException e) {
            Log.e(TAG, "Could not get routing info for account", e);
            return null;
        }

	ContentResolver cr = context.getContentResolver();
	boolean useRmq2RoutingInfo = "true".equals(
	    Settings.Gservices.getString(cr, Settings.Gservices.GSYNC_USE_RMQ2_ROUTING_INFO));
        if (useRmq2RoutingInfo) {
	    return Uri.parse("android://" + Long.toHexString(androidId)).toString();
	} else {
            // We have to send the routing info for the primary (legacy) account.
            final AccountManager accountManager = AccountManager.get(getContext());
            try {
                String[] features =
                        new String[]{GoogleLoginServiceConstants.FEATURE_LEGACY_HOSTED_OR_GOOGLE};
                Account[] accounts = accountManager.getAccountsByTypeAndFeatures(
                        GoogleLoginServiceConstants.ACCOUNT_TYPE, features, null, null).getResult();
                if (accounts.length == 0) {
                    Log.e(TAG, "No matching accounts.");
                    return null;
                }
                return Uri.parse("gtalk://" + accounts[0].name
                        + "#" + Settings.getGTalkDeviceId(androidId)).toString();
            } catch (IOException e) {
                Log.e(TAG, "Could not get account", e);
            } catch (AuthenticatorException e) {
                Log.e(TAG, "Could not get account", e);
            } catch (OperationCanceledException e) {
                Log.e(TAG, "Could not get account", e);
            }
        }
        return null;
    }

    @Override
    protected boolean hasTooManyDeletions(SyncStats stats) {
        // allow subscribed feeds to delete any number of entries,
        // since the client is the authority on which entries
        // should exist.
        return false;
    }
}
