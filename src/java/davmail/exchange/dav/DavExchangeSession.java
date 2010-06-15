/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2010  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package davmail.exchange.dav;

import davmail.BundleMessage;
import davmail.Settings;
import davmail.exception.DavMailAuthenticationException;
import davmail.exception.DavMailException;
import davmail.exception.HttpNotFoundException;
import davmail.exchange.ExchangeSession;
import davmail.http.DavGatewayHttpClientFacade;
import davmail.ui.tray.DavGatewayTray;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.httpclient.util.URIUtil;
import org.apache.jackrabbit.webdav.DavConstants;
import org.apache.jackrabbit.webdav.MultiStatusResponse;
import org.apache.jackrabbit.webdav.client.methods.CopyMethod;
import org.apache.jackrabbit.webdav.client.methods.MoveMethod;
import org.apache.jackrabbit.webdav.client.methods.PropFindMethod;
import org.apache.jackrabbit.webdav.client.methods.PropPatchMethod;
import org.apache.jackrabbit.webdav.property.*;
import org.apache.jackrabbit.webdav.xml.Namespace;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Webdav Exchange adapter.
 * Compatible with Exchange 2003 and 2007 with webdav available.
 */
public class DavExchangeSession extends ExchangeSession {
    protected static final DavPropertyNameSet WELL_KNOWN_FOLDERS = new DavPropertyNameSet();

    static {
        WELL_KNOWN_FOLDERS.add(Field.get("inbox").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("deleteditems").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("sentitems").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("sendmsg").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("drafts").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("calendar").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("contacts").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("outbox").davPropertyName);
    }

    /**
     * @inheritDoc
     */
    public DavExchangeSession(String url, String userName, String password) throws IOException {
        super(url, userName, password);
    }

    @Override
    protected void buildSessionInfo(HttpMethod method) throws DavMailException {
        buildMailPath(method);

        // get base http mailbox http urls
        getWellKnownFolders();
    }

    static final String BASE_HREF = "<base href=\"";

    protected void buildMailPath(HttpMethod method) throws DavMailAuthenticationException {
        // find base url
        String line;

        // get user mail URL from html body (multi frame)
        BufferedReader mainPageReader = null;
        try {
            mainPageReader = new BufferedReader(new InputStreamReader(method.getResponseBodyAsStream()));
            //noinspection StatementWithEmptyBody
            while ((line = mainPageReader.readLine()) != null && line.toLowerCase().indexOf(BASE_HREF) == -1) {
            }
            if (line != null) {
                int start = line.toLowerCase().indexOf(BASE_HREF) + BASE_HREF.length();
                int end = line.indexOf('\"', start);
                String mailBoxBaseHref = line.substring(start, end);
                URL baseURL = new URL(mailBoxBaseHref);
                mailPath = baseURL.getPath();
                LOGGER.debug("Base href found in body, mailPath is " + mailPath);
                buildEmail(method.getURI().getHost());
                LOGGER.debug("Current user email is " + email);
            } else {
                // failover for Exchange 2007 : build standard mailbox link with email
                buildEmail(method.getURI().getHost());
                mailPath = "/exchange/" + email + '/';
                LOGGER.debug("Current user email is " + email + ", mailPath is " + mailPath);
            }
        } catch (IOException e) {
            LOGGER.error("Error parsing main page at " + method.getPath(), e);
        } finally {
            if (mainPageReader != null) {
                try {
                    mainPageReader.close();
                } catch (IOException e) {
                    LOGGER.error("Error parsing main page at " + method.getPath());
                }
            }
            method.releaseConnection();
        }


        if (mailPath == null || email == null) {
            throw new DavMailAuthenticationException("EXCEPTION_AUTHENTICATION_FAILED_PASSWORD_EXPIRED");
        }
    }

    protected String getURIPropertyIfExists(DavPropertySet properties, String alias) throws URIException {
        DavProperty property = properties.get(Field.get(alias).davPropertyName);
        if (property == null) {
            return null;
        } else {
            return URIUtil.decode((String) property.getValue());
        }
    }

    protected void getWellKnownFolders() throws DavMailException {
        // Retrieve well known URLs
        MultiStatusResponse[] responses;
        try {
            responses = DavGatewayHttpClientFacade.executePropFindMethod(
                    httpClient, URIUtil.encodePath(mailPath), 0, WELL_KNOWN_FOLDERS);
            if (responses.length == 0) {
                throw new DavMailException("EXCEPTION_UNABLE_TO_GET_MAIL_FOLDER", mailPath);
            }
            DavPropertySet properties = responses[0].getProperties(HttpStatus.SC_OK);
            inboxUrl = getURIPropertyIfExists(properties, "inbox");
            deleteditemsUrl = getURIPropertyIfExists(properties, "deleteditems");
            sentitemsUrl = getURIPropertyIfExists(properties, "sentitems");
            sendmsgUrl = getURIPropertyIfExists(properties, "sendmsg");
            draftsUrl = getURIPropertyIfExists(properties, "drafts");
            calendarUrl = getURIPropertyIfExists(properties, "calendar");
            contactsUrl = getURIPropertyIfExists(properties, "contacts");
            outboxUrl = getURIPropertyIfExists(properties, "outbox");
            // junk folder not available over webdav

            // default public folder path
            publicFolderUrl = PUBLIC_ROOT;

            // check public folder access
            try {
                if (inboxUrl != null) {
                    // try to build full public URI from inboxUrl
                    URI publicUri = new URI(inboxUrl, false);
                    publicUri.setPath(PUBLIC_ROOT);
                    publicFolderUrl = publicUri.getURI();
                }
                PropFindMethod propFindMethod = new PropFindMethod(publicFolderUrl, CONTENT_TAG, 0);
                try {
                    DavGatewayHttpClientFacade.executeMethod(httpClient, propFindMethod);
                } catch (IOException e) {
                    // workaround for NTLM authentication only on /public
                    if (!DavGatewayHttpClientFacade.hasNTLM(httpClient)) {
                        DavGatewayHttpClientFacade.addNTLM(httpClient);
                        DavGatewayHttpClientFacade.executeMethod(httpClient, propFindMethod);
                    }
                }
                // update public folder URI
                publicFolderUrl = propFindMethod.getURI().getURI();
            } catch (IOException e) {
                LOGGER.warn("Public folders not available: " + (e.getMessage() == null ? e : e.getMessage()));
                publicFolderUrl = "/public";
            }

            LOGGER.debug("Inbox URL: " + inboxUrl +
                    " Trash URL: " + deleteditemsUrl +
                    " Sent URL: " + sentitemsUrl +
                    " Send URL: " + sendmsgUrl +
                    " Drafts URL: " + draftsUrl +
                    " Calendar URL: " + calendarUrl +
                    " Contacts URL: " + contactsUrl +
                    " Outbox URL: " + outboxUrl +
                    " Public folder URL: " + publicFolderUrl
            );
        } catch (IOException e) {
            LOGGER.error(e.getMessage());
            throw new DavMailAuthenticationException("EXCEPTION_UNABLE_TO_GET_MAIL_FOLDER", mailPath);
        }
    }

    /**
     * @inheritDoc
     */
    @Override
    public boolean isExpired() throws NoRouteToHostException, UnknownHostException {
        boolean isExpired = false;
        try {
            DavGatewayHttpClientFacade.executePropFindMethod(
                    httpClient, URIUtil.encodePath(inboxUrl), 0, DISPLAY_NAME);
        } catch (UnknownHostException exc) {
            throw exc;
        } catch (NoRouteToHostException exc) {
            throw exc;
        } catch (IOException e) {
            isExpired = true;
        }

        return isExpired;
    }

    protected static class MultiCondition extends ExchangeSession.MultiCondition {
        protected MultiCondition(Operator operator, Condition... condition) {
            super(operator, condition);
        }

        @Override
        public void appendTo(StringBuilder buffer) {
            boolean first = true;

            for (Condition condition : conditions) {
                if (condition != null) {
                    if (first) {
                        buffer.append('(');
                        first = false;
                    } else {
                        buffer.append(' ').append(operator).append(' ');
                    }
                    condition.appendTo(buffer);
                }
            }
            // at least one non empty condition
            if (!first) {
                buffer.append(')');
            }
        }
    }

    protected static class NotCondition extends ExchangeSession.NotCondition {
        protected NotCondition(Condition condition) {
            super(condition);
        }

        @Override
        public void appendTo(StringBuilder buffer) {
            boolean first = true;
            buffer.append("( Not ");
            condition.appendTo(buffer);
            buffer.append(')');
        }
    }

    static final Map<Operator, String> operatorMap = new HashMap<Operator, String>();

    static {
        operatorMap.put(Operator.IsEqualTo, " = ");
        operatorMap.put(Operator.IsGreaterThanOrEqualTo, " >= ");
        operatorMap.put(Operator.IsGreaterThan, " > ");
        operatorMap.put(Operator.IsLessThan, " < ");
        operatorMap.put(Operator.Like, " like ");
        operatorMap.put(Operator.IsNull, " is null");
        operatorMap.put(Operator.IsFalse, " is false");
    }

    protected static class AttributeCondition extends ExchangeSession.AttributeCondition {
        protected AttributeCondition(String attributeName, Operator operator, String value) {
            super(attributeName, operator, value);
        }

        @Override
        public void appendTo(StringBuilder buffer) {
            buffer.append('"').append(Field.get(attributeName).getUri()).append('"');
            buffer.append(operatorMap.get(operator));
            buffer.append('\'');
            if (Operator.Like == operator) {
                buffer.append('%');
            }
            buffer.append(value);
            if (Operator.Like == operator) {
                buffer.append('%');
            }
            buffer.append('\'');
        }
    }

    protected static class HeaderCondition extends AttributeCondition {

        protected HeaderCondition(String attributeName, Operator operator, String value) {
            super(attributeName, operator, value);
        }

        @Override
        public void appendTo(StringBuilder buffer) {
            buffer.append('"').append(Field.getHeader(attributeName)).append('"');
            buffer.append(operatorMap.get(operator));
            buffer.append('\'');
            if (Operator.Like == operator) {
                buffer.append('%');
            }
            buffer.append(value);
            if (Operator.Like == operator) {
                buffer.append('%');
            }
            buffer.append('\'');
        }
    }

    protected static class MonoCondition extends ExchangeSession.MonoCondition {
        protected MonoCondition(String attributeName, Operator operator) {
            super(attributeName, operator);
        }

        @Override
        public void appendTo(StringBuilder buffer) {
            buffer.append('"').append(Field.get(attributeName).getUri()).append('"');
            buffer.append(operatorMap.get(operator));
        }
    }

    @Override
    public MultiCondition and(Condition... condition) {
        return new MultiCondition(Operator.And, condition);
    }

    @Override
    public MultiCondition or(Condition... condition) {
        return new MultiCondition(Operator.Or, condition);
    }

    @Override
    public Condition not(Condition condition) {
        if (condition == null) {
            return null;
        } else {
            return new NotCondition(condition);
        }
    }

    @Override
    public Condition equals(String attributeName, String value) {
        return new AttributeCondition(attributeName, Operator.IsEqualTo, value);
    }

    @Override
    public Condition headerEquals(String headerName, String value) {
        return new HeaderCondition(headerName, Operator.IsEqualTo, value);
    }

    @Override
    public Condition gte(String attributeName, String value) {
        return new AttributeCondition(attributeName, Operator.IsGreaterThanOrEqualTo, value);
    }

    @Override
    public Condition lt(String attributeName, String value) {
        return new AttributeCondition(attributeName, Operator.IsLessThan, value);
    }

    @Override
    public Condition gt(String attributeName, String value) {
        return new AttributeCondition(attributeName, Operator.IsGreaterThan, value);
    }

    @Override
    public Condition like(String attributeName, String value) {
        return new AttributeCondition(attributeName, Operator.Like, value);
    }

    @Override
    public Condition isNull(String attributeName) {
        return new MonoCondition(attributeName, Operator.IsNull);
    }

    @Override
    public Condition isTrue(String attributeName) {
        return new MonoCondition(attributeName, Operator.IsTrue);
    }

    @Override
    public Condition isFalse(String attributeName) {
        return new MonoCondition(attributeName, Operator.IsFalse);
    }

    public class Contact extends ExchangeSession.Contact {
        /**
         * Build Contact instance from multistatusResponse info
         *
         * @param multiStatusResponse response
         * @throws URIException on error
         */
        public Contact(MultiStatusResponse multiStatusResponse) throws URIException {
            href = URIUtil.decode(multiStatusResponse.getHref());
            DavPropertySet properties = multiStatusResponse.getProperties(HttpStatus.SC_OK);
            permanentUrl = getPropertyIfExists(properties, "permanenturl", SCHEMAS_EXCHANGE);
            etag = getPropertyIfExists(properties, "getetag", DAV);
            displayName = getPropertyIfExists(properties, "displayname", DAV);
        }

        /**
         * @inheritDoc
         */
        public Contact(String messageUrl, String contentClass, String itemBody, String etag, String noneMatch) {
            super(messageUrl, contentClass, itemBody, etag, noneMatch);
        }

    }

    public class Event extends ExchangeSession.Event {
        /**
         * Build Event instance from response info.
         *
         * @param multiStatusResponse response
         * @throws URIException on error
         */
        public Event(MultiStatusResponse multiStatusResponse) throws URIException {
            href = URIUtil.decode(multiStatusResponse.getHref());
            DavPropertySet properties = multiStatusResponse.getProperties(HttpStatus.SC_OK);
            permanentUrl = getPropertyIfExists(properties, "permanenturl", SCHEMAS_EXCHANGE);
            etag = getPropertyIfExists(properties, "getetag", DAV);
            displayName = getPropertyIfExists(properties, "displayname", DAV);
        }

        public Event(String messageUrl, String contentClass, String itemBody, String etag, String noneMatch) {
            super(messageUrl, contentClass, itemBody, etag, noneMatch);
        }

        protected ItemResult createOrUpdate(byte[] messageContent) throws IOException {
            PutMethod putmethod = new PutMethod(URIUtil.encodePath(href));
            putmethod.setRequestHeader("Translate", "f");
            putmethod.setRequestHeader("Overwrite", "f");
            if (etag != null) {
                putmethod.setRequestHeader("If-Match", etag);
            }
            if (noneMatch != null) {
                putmethod.setRequestHeader("If-None-Match", noneMatch);
            }
            putmethod.setRequestHeader("Content-Type", "message/rfc822");
            putmethod.setRequestEntity(new ByteArrayRequestEntity(messageContent, "message/rfc822"));
            int status;
            try {
                status = httpClient.executeMethod(putmethod);
                if (status == HttpURLConnection.HTTP_OK) {
                    if (etag != null) {
                        LOGGER.debug("Updated event " + href);
                    } else {
                        LOGGER.warn("Overwritten event " + href);
                    }
                } else if (status != HttpURLConnection.HTTP_CREATED) {
                    LOGGER.warn("Unable to create or update message " + status + ' ' + putmethod.getStatusLine());
                }
            } finally {
                putmethod.releaseConnection();
            }
            ItemResult itemResult = new ItemResult();
            // 440 means forbidden on Exchange
            if (status == 440) {
                status = HttpStatus.SC_FORBIDDEN;
            }
            itemResult.status = status;
            if (putmethod.getResponseHeader("GetETag") != null) {
                itemResult.etag = putmethod.getResponseHeader("GetETag").getValue();
            }

            // trigger activeSync push event, only if davmail.forceActiveSyncUpdate setting is true
            if ((status == HttpStatus.SC_OK || status == HttpStatus.SC_CREATED) &&
                    (Settings.getBooleanProperty("davmail.forceActiveSyncUpdate"))) {
                ArrayList<DavConstants> propertyList = new ArrayList<DavConstants>();
                // Set contentclass to make ActiveSync happy
                propertyList.add(Field.createDavProperty("contentclass", contentClass));
                // ... but also set PR_INTERNET_CONTENT to preserve custom properties
                propertyList.add(Field.createDavProperty("internetContent", new String(Base64.encodeBase64(messageContent))));
                PropPatchMethod propPatchMethod = new PropPatchMethod(URIUtil.encodePath(href), propertyList);
                int patchStatus = DavGatewayHttpClientFacade.executeHttpMethod(httpClient, propPatchMethod);
                if (patchStatus != HttpStatus.SC_MULTI_STATUS) {
                    LOGGER.warn("Unable to patch event to trigger activeSync push");
                } else {
                    // need to retrieve new etag
                    Item newItem = getItem(href);
                    itemResult.etag = newItem.etag;
                }
            }
            return itemResult;
        }


    }

    protected Folder buildFolder(MultiStatusResponse entity) throws IOException {
        String href = URIUtil.decode(entity.getHref());
        Folder folder = new Folder();
        DavPropertySet properties = entity.getProperties(HttpStatus.SC_OK);
        folder.folderClass = getPropertyIfExists(properties, "outlookfolderclass", SCHEMAS_EXCHANGE);
        folder.hasChildren = "1".equals(getPropertyIfExists(properties, "hassubs", DAV));
        folder.noInferiors = "1".equals(getPropertyIfExists(properties, "nosubs", DAV));
        folder.unreadCount = getIntPropertyIfExists(properties, "unreadcount", URN_SCHEMAS_HTTPMAIL);
        folder.ctag = getPropertyIfExists(properties, "contenttag", Namespace.getNamespace("http://schemas.microsoft.com/repl/"));
        folder.etag = getPropertyIfExists(properties, "x30080040", SCHEMAS_MAPI_PROPTAG);

        // replace well known folder names
        if (href.startsWith(inboxUrl)) {
            folder.folderPath = href.replaceFirst(inboxUrl, INBOX);
        } else if (href.startsWith(sentitemsUrl)) {
            folder.folderPath = href.replaceFirst(sentitemsUrl, SENT);
        } else if (href.startsWith(draftsUrl)) {
            folder.folderPath = href.replaceFirst(draftsUrl, DRAFTS);
        } else if (href.startsWith(deleteditemsUrl)) {
            folder.folderPath = href.replaceFirst(deleteditemsUrl, TRASH);
        } else if (href.startsWith(calendarUrl)) {
            folder.folderPath = href.replaceFirst(calendarUrl, CALENDAR);
        } else if (href.startsWith(contactsUrl)) {
            folder.folderPath = href.replaceFirst(contactsUrl, CONTACTS);
        } else {
            int index = href.indexOf(mailPath.substring(0, mailPath.length() - 1));
            if (index >= 0) {
                if (index + mailPath.length() > href.length()) {
                    folder.folderPath = "";
                } else {
                    folder.folderPath = href.substring(index + mailPath.length());
                }
            } else {
                try {
                    URI folderURI = new URI(href, false);
                    folder.folderPath = folderURI.getPath();
                } catch (URIException e) {
                    throw new DavMailException("EXCEPTION_INVALID_FOLDER_URL", href);
                }
            }
        }
        if (folder.folderPath.endsWith("/")) {
            folder.folderPath = folder.folderPath.substring(0, folder.folderPath.length() - 1);
        }
        return folder;
    }

    protected static final DavPropertyNameSet FOLDER_PROPERTIES = new DavPropertyNameSet();

    static {
        FOLDER_PROPERTIES.add(Field.get("folderclass").davPropertyName);
        FOLDER_PROPERTIES.add(Field.get("hassubs").davPropertyName);
        FOLDER_PROPERTIES.add(Field.get("nosubs").davPropertyName);
        FOLDER_PROPERTIES.add(Field.get("unreadcount").davPropertyName);
        FOLDER_PROPERTIES.add(Field.get("contenttag").davPropertyName);
        FOLDER_PROPERTIES.add(Field.get("lastmodified").davPropertyName);
    }


    /**
     * @inheritDoc
     */
    @Override
    public Folder getFolder(String folderPath) throws IOException {
        MultiStatusResponse[] responses = DavGatewayHttpClientFacade.executePropFindMethod(
                httpClient, URIUtil.encodePath(getFolderPath(folderPath)), 0, FOLDER_PROPERTIES);
        Folder folder = null;
        if (responses.length > 0) {
            folder = buildFolder(responses[0]);
            folder.folderPath = folderPath;
        }
        return folder;
    }

    /**
     * @inheritDoc
     */
    @Override
    public List<Folder> getSubFolders(String folderName, Condition condition, boolean recursive) throws IOException {
        boolean isPublic = folderName.startsWith("/public");
        String mode = (!isPublic && recursive) ? "DEEP" : "SHALLOW";
        List<Folder> folders = new ArrayList<Folder>();
        StringBuilder searchRequest = new StringBuilder();
        searchRequest.append("Select \"DAV:nosubs\", \"DAV:hassubs\", \"http://schemas.microsoft.com/exchange/outlookfolderclass\", " +
                "\"http://schemas.microsoft.com/repl/contenttag\", \"http://schemas.microsoft.com/mapi/proptag/x30080040\", " +
                "\"urn:schemas:httpmail:unreadcount\" FROM Scope('").append(mode).append(" TRAVERSAL OF \"").append(getFolderPath(folderName)).append("\"')\n" +
                " WHERE \"DAV:ishidden\" = False AND \"DAV:isfolder\" = True \n");
        if (condition != null) {
            searchRequest.append(" AND ");
            condition.appendTo(searchRequest);
        }
        MultiStatusResponse[] responses = DavGatewayHttpClientFacade.executeSearchMethod(
                httpClient, URIUtil.encodePath(getFolderPath(folderName)), searchRequest.toString());

        for (MultiStatusResponse response : responses) {
            Folder folder = buildFolder(response);
            folders.add(buildFolder(response));
            if (isPublic && recursive) {
                getSubFolders(folder.folderPath, condition, recursive);
            }
        }
        return folders;
    }

    /**
     * @inheritDoc
     */
    public void createFolder(String folderName, String folderClass) throws IOException {
        String folderPath = getFolderPath(folderName);
        ArrayList<DavConstants> list = new ArrayList<DavConstants>();
        list.add(Field.createDavProperty("folderclass", folderClass));
        // standard MkColMethod does not take properties, override PropPatchMethod instead
        PropPatchMethod method = new PropPatchMethod(URIUtil.encodePath(folderPath), list) {
            @Override
            public String getName() {
                return "MKCOL";
            }
        };
        int status = DavGatewayHttpClientFacade.executeHttpMethod(httpClient, method);
        // ok or already exists
        if (status != HttpStatus.SC_MULTI_STATUS && status != HttpStatus.SC_METHOD_NOT_ALLOWED) {
            throw DavGatewayHttpClientFacade.buildHttpException(method);
        }
    }

    /**
     * @inheritDoc
     */
    @Override
    public void deleteFolder(String folderName) throws IOException {
        DavGatewayHttpClientFacade.executeDeleteMethod(httpClient, URIUtil.encodePath(getFolderPath(folderName)));
    }

    /**
     * @inheritDoc
     */
    @Override
    public void moveFolder(String folderName, String targetName) throws IOException {
        String folderPath = getFolderPath(folderName);
        String targetPath = getFolderPath(targetName);
        MoveMethod method = new MoveMethod(URIUtil.encodePath(folderPath), URIUtil.encodePath(targetPath), false);
        try {
            int statusCode = httpClient.executeMethod(method);
            if (statusCode == HttpStatus.SC_PRECONDITION_FAILED) {
                throw new DavMailException("EXCEPTION_UNABLE_TO_MOVE_FOLDER");
            } else if (statusCode != HttpStatus.SC_CREATED) {
                throw DavGatewayHttpClientFacade.buildHttpException(method);
            }
        } finally {
            method.releaseConnection();
        }
    }

    protected String getPropertyIfExists(DavPropertySet properties, String name) {
        DavProperty property = properties.get(name, EMPTY);
        if (property == null) {
            return null;
        } else {
            return (String) property.getValue();
        }
    }

    protected int getIntPropertyIfExists(DavPropertySet properties, String name) {
        DavProperty property = properties.get(name, EMPTY);
        if (property == null) {
            return 0;
        } else {
            return Integer.parseInt((String) property.getValue());
        }
    }

    protected long getLongPropertyIfExists(DavPropertySet properties, String name) {
        DavProperty property = properties.get(name, EMPTY);
        if (property == null) {
            return 0;
        } else {
            return Long.parseLong((String) property.getValue());
        }
    }


    protected Message buildMessage(MultiStatusResponse responseEntity) throws URIException {
        Message message = new Message();
        message.messageUrl = URIUtil.decode(responseEntity.getHref());
        DavPropertySet properties = responseEntity.getProperties(HttpStatus.SC_OK);

        message.permanentUrl = getPropertyIfExists(properties, "permanenturl");
        message.size = getIntPropertyIfExists(properties, "messageSize");
        message.uid = getPropertyIfExists(properties, "uid");
        message.imapUid = getLongPropertyIfExists(properties, "imapUid");
        message.read = "1".equals(getPropertyIfExists(properties, "read"));
        message.junk = "1".equals(getPropertyIfExists(properties, "junk"));
        message.flagged = "2".equals(getPropertyIfExists(properties, "flagStatus"));
        message.draft = "9".equals(getPropertyIfExists(properties, "messageFlags"));
        String lastVerbExecuted = getPropertyIfExists(properties, "lastVerbExecuted");
        message.answered = "102".equals(lastVerbExecuted) || "103".equals(lastVerbExecuted);
        message.forwarded = "104".equals(lastVerbExecuted);
        message.date = getPropertyIfExists(properties, "date");
        message.deleted = "1".equals(getPropertyIfExists(properties, "deleted"));

        if (LOGGER.isDebugEnabled()) {
            StringBuilder buffer = new StringBuilder();
            buffer.append("Message");
            if (message.imapUid != 0) {
                buffer.append(" IMAP uid: ").append(message.imapUid);
            }
            if (message.uid != null) {
                buffer.append(" uid: ").append(message.uid);
            }
            buffer.append(" href: ").append(responseEntity.getHref()).append(" permanenturl:").append(message.permanentUrl);
            LOGGER.debug(buffer.toString());
        }
        return message;
    }

    @Override
    public MessageList searchMessages(String folderName, List<String> attributes, Condition condition) throws IOException {
        MessageList messages = new MessageList();
        MultiStatusResponse[] responses = searchItems(folderName, attributes, condition);

        for (MultiStatusResponse response : responses) {
            Message message = buildMessage(response);
            message.messageList = messages;
            messages.add(message);
        }
        Collections.sort(messages);
        return messages;
    }

    /**
     * @inheritDoc
     */
    @Override
    protected List<ExchangeSession.Contact> searchContacts(String folderName, List<String> attributes, Condition condition) throws IOException {
        List<ExchangeSession.Contact> contacts = new ArrayList<ExchangeSession.Contact>();
        MultiStatusResponse[] responses = searchItems(folderName, attributes, condition);
        for (MultiStatusResponse response : responses) {
            contacts.add(new Contact(response));
        }
        return contacts;
    }

    @Override
    protected List<ExchangeSession.Event> searchEvents(String folderName, List<String> attributes, Condition condition) throws IOException {
        List<ExchangeSession.Event> events = new ArrayList<ExchangeSession.Event>();
        MultiStatusResponse[] responses = searchItems(folderName, attributes, condition);
        for (MultiStatusResponse response : responses) {
            String instancetype = getPropertyIfExists(response.getProperties(HttpStatus.SC_OK), "instancetype");
            Event event = new Event(response);
            //noinspection VariableNotUsedInsideIf
            if (instancetype == null) {
                // check ics content
                try {
                    event.getBody();
                    // getBody success => add event or task
                    events.add(event);
                } catch (HttpException e) {
                    // invalid event: exclude from list
                    LOGGER.warn("Invalid event " + event.displayName + " found at " + response.getHref(), e);
                }
            } else {
                events.add(event);
            }
        }
        return events;
    }

    public MultiStatusResponse[] searchItems(String folderName, List<String> attributes, Condition condition) throws IOException {
        String folderUrl = getFolderPath(folderName);
        StringBuilder searchRequest = new StringBuilder();
        searchRequest.append("Select \"http://schemas.microsoft.com/exchange/permanenturl\" as permanenturl");
        if (attributes != null) {
            for (String attribute : attributes) {
                Field field = Field.get(attribute);
                searchRequest.append(",\"").append(field.getUri()).append("\" as ").append(field.getAlias());
            }
        }
        searchRequest.append(" FROM Scope('SHALLOW TRAVERSAL OF \"").append(folderUrl).append("\"')")
                .append(" WHERE \"DAV:ishidden\" = False AND \"DAV:isfolder\" = False");
        if (condition != null) {
            searchRequest.append(" AND ");
            condition.appendTo(searchRequest);
        }
        // TODO order by ImapUid
        //searchRequest.append("       ORDER BY \"urn:schemas:httpmail:date\" ASC");
        DavGatewayTray.debug(new BundleMessage("LOG_SEARCH_QUERY", searchRequest));
        return DavGatewayHttpClientFacade.executeSearchMethod(
                httpClient, URIUtil.encodePath(folderUrl), searchRequest.toString());
    }


    protected static final DavPropertyNameSet EVENT_REQUEST_PROPERTIES = new DavPropertyNameSet();

    static {
        EVENT_REQUEST_PROPERTIES.add(Field.get("permanenturl").davPropertyName);
        EVENT_REQUEST_PROPERTIES.add(Field.get("etag").davPropertyName);
        EVENT_REQUEST_PROPERTIES.add(Field.get("contentclass").davPropertyName);
        EVENT_REQUEST_PROPERTIES.add(Field.get("displayname").davPropertyName);
    }

    @Override
    public Item getItem(String folderPath, String itemName) throws IOException {
        String itemPath = folderPath + '/' + convertItemNameToEML(itemName);
        Item item;
        try {
            item = getItem(itemPath);
        } catch (HttpNotFoundException hnfe) {
            // failover for Exchange 2007 plus encoding issue
            String decodedEventName = convertItemNameToEML(itemName).replaceAll("_xF8FF_", "/").replaceAll("_x003F_", "?").replaceAll("'", "''");
            LOGGER.debug("Item not found at " + itemPath + ", search by displayname: '" + decodedEventName + '\'');
            ExchangeSession.MessageList messages = searchMessages(folderPath, equals("displayname", decodedEventName));
            if (!messages.isEmpty()) {
                item = getItem(messages.get(0).getPermanentUrl());
            } else {
                throw hnfe;
            }
        }

        return item;
    }

    /**
     * Get item by url
     *
     * @param itemPath Event path
     * @return event object
     * @throws IOException on error
     */
    public Item getItem(String itemPath) throws IOException {
        MultiStatusResponse[] responses = DavGatewayHttpClientFacade.executePropFindMethod(httpClient, URIUtil.encodePath(itemPath), 0, EVENT_REQUEST_PROPERTIES);
        if (responses.length == 0) {
            throw new DavMailException("EXCEPTION_EVENT_NOT_FOUND");
        }
        String contentClass = getPropertyIfExists(responses[0].getProperties(HttpStatus.SC_OK),
                "contentclass", DAV);
        if ("urn:content-classes:person".equals(contentClass)) {
            return new Contact(responses[0]);
        } else if ("urn:content-classes:appointment".equals(contentClass)
                || "urn:content-classes:calendarmessage".equals(contentClass)) {
            return new Event(responses[0]);
        } else {
            throw new DavMailException("EXCEPTION_EVENT_NOT_FOUND");
        }
    }

    public ItemResult internalCreateOrUpdateEvent(String messageUrl, String contentClass, String icsBody, String etag, String noneMatch) throws IOException {
        Event event = new Event(messageUrl, contentClass, icsBody, etag, noneMatch);
        return event.createOrUpdate();
    }

    protected ItemResult internalCreateOrUpdateContact(String messageUrl, String contentClass, String icsBody, String etag, String noneMatch) throws IOException {
        Contact contact = new Contact(messageUrl, contentClass, icsBody, etag, noneMatch);
        return contact.createOrUpdate();
    }

    protected List<DavConstants> buildProperties(Map<String, String> properties) {
        ArrayList<DavConstants> list = new ArrayList<DavConstants>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if ("read".equals(entry.getKey())) {
                list.add(Field.createDavProperty("read", entry.getValue()));
            } else if ("junk".equals(entry.getKey())) {
                list.add(Field.createDavProperty("junk", entry.getValue()));
            } else if ("flagged".equals(entry.getKey())) {
                list.add(Field.createDavProperty("flagStatus", entry.getValue()));
            } else if ("answered".equals(entry.getKey())) {
                list.add(Field.createDavProperty("lastVerbExecuted", entry.getValue()));
                if ("102".equals(entry.getValue())) {
                    list.add(Field.createDavProperty("iconIndex", "261"));
                }
            } else if ("forwarded".equals(entry.getKey())) {
                list.add(Field.createDavProperty("lastVerbExecuted", entry.getValue()));
                if ("104".equals(entry.getValue())) {
                    list.add(Field.createDavProperty("iconIndex", "262"));
                }
            } else if ("bcc".equals(entry.getKey())) {
                list.add(Field.createDavProperty("bcc", entry.getValue()));
            } else if ("draft".equals(entry.getKey())) {
                list.add(Field.createDavProperty("messageFlags", entry.getValue()));
            } else if ("deleted".equals(entry.getKey())) {
                // TODO: need to test this
                list.add(Field.createDavProperty("writedeleted", entry.getValue()));
            } else if ("datereceived".equals(entry.getKey())) {
                list.add(new DefaultDavProperty(DavPropertyName.create("datereceived", URN_SCHEMAS_HTTPMAIL), entry.getValue()));
            }
        }
        return list;
    }

    /**
     * Create message in specified folder.
     * Will overwrite an existing message with same subject in the same folder
     *
     * @param folderPath  Exchange folder path
     * @param messageName message name
     * @param properties  message properties (flags)
     * @param messageBody mail body
     * @throws IOException when unable to create message
     */
    @Override
    public void createMessage(String folderPath, String messageName, HashMap<String, String> properties, String messageBody) throws IOException {
        String messageUrl = URIUtil.encodePathQuery(getFolderPath(folderPath) + '/' + messageName + ".EML");
        PropPatchMethod patchMethod;
        // create the message first as draft
        if (properties.containsKey("draft")) {
            patchMethod = new PropPatchMethod(messageUrl, buildProperties(properties));
            try {
                // update message with blind carbon copy and other flags
                int statusCode = httpClient.executeMethod(patchMethod);
                if (statusCode != HttpStatus.SC_MULTI_STATUS) {
                    throw new DavMailException("EXCEPTION_UNABLE_TO_CREATE_MESSAGE", messageUrl, statusCode, ' ', patchMethod.getStatusLine());
                }

            } finally {
                patchMethod.releaseConnection();
            }
        }

        PutMethod putmethod = new PutMethod(messageUrl);
        putmethod.setRequestHeader("Translate", "f");
        try {
            // use same encoding as client socket reader
            putmethod.setRequestEntity(new ByteArrayRequestEntity(messageBody.getBytes()));
            int code = httpClient.executeMethod(putmethod);

            if (code != HttpStatus.SC_OK && code != HttpStatus.SC_CREATED) {
                throw new DavMailException("EXCEPTION_UNABLE_TO_CREATE_MESSAGE", messageUrl, code, ' ', putmethod.getStatusLine());
            }
        } finally {
            putmethod.releaseConnection();
        }

        // add bcc and other properties
        if (!properties.isEmpty()) {
            patchMethod = new PropPatchMethod(messageUrl, buildProperties(properties));
            try {
                // update message with blind carbon copy and other flags
                int statusCode = httpClient.executeMethod(patchMethod);
                if (statusCode != HttpStatus.SC_MULTI_STATUS) {
                    throw new DavMailException("EXCEPTION_UNABLE_TO_PATCH_MESSAGE", messageUrl, statusCode, ' ', patchMethod.getStatusLine());
                }

            } finally {
                patchMethod.releaseConnection();
            }
        }
    }

    /**
     * @inheritDoc
     */
    @Override
    public void updateMessage(Message message, Map<String, String> properties) throws IOException {
        PropPatchMethod patchMethod = new PropPatchMethod(message.permanentUrl, buildProperties(properties)) {
            @Override
            protected void processResponseBody(HttpState httpState, HttpConnection httpConnection) {
                // ignore response body, sometimes invalid with exchange mapi properties
            }
        };
        try {
            int statusCode = httpClient.executeMethod(patchMethod);
            if (statusCode != HttpStatus.SC_MULTI_STATUS) {
                throw new DavMailException("EXCEPTION_UNABLE_TO_UPDATE_MESSAGE");
            }

        } finally {
            patchMethod.releaseConnection();
        }
    }

    /**
     * @inheritDoc
     */
    @Override
    public void deleteMessage(Message message) throws IOException {
        LOGGER.debug("Delete " + message.permanentUrl + " (" + message.messageUrl + ")");
        DavGatewayHttpClientFacade.executeDeleteMethod(httpClient, message.permanentUrl);
    }

    /**
     * @inheritDoc
     */
    @Override
    public void sendMessage(HashMap<String, String> properties, String messageBody) throws IOException {
        String messageName = UUID.randomUUID().toString();

        createMessage("Drafts", messageName, properties, messageBody);

        String tempUrl = draftsUrl + '/' + messageName + ".EML";
        MoveMethod method = new MoveMethod(URIUtil.encodePath(tempUrl), URIUtil.encodePath(sendmsgUrl), true);
        int status = DavGatewayHttpClientFacade.executeHttpMethod(httpClient, method);
        if (status != HttpStatus.SC_OK) {
            throw DavGatewayHttpClientFacade.buildHttpException(method);
        }
    }

    protected boolean isGzipEncoded(HttpMethod method) {
        Header[] contentEncodingHeaders = method.getResponseHeaders("Content-Encoding");
        if (contentEncodingHeaders != null) {
            for (Header header : contentEncodingHeaders) {
                if ("gzip".equals(header.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @inheritDoc
     */
    @Override
    protected BufferedReader getContentReader(Message message) throws IOException {
        BufferedReader reader = null;
        try {
            reader = getContentReader(message, message.messageUrl);
        } catch (HttpNotFoundException e) {
            LOGGER.debug("Message not found at: " + message.messageUrl + ", retrying with permanenturl");
            reader = getContentReader(message, message.permanentUrl);
        }
        return reader;
    }

    protected BufferedReader getContentReader(Message message, String url) throws IOException {
        final GetMethod method = new GetMethod(URIUtil.encodePath(message.permanentUrl));
        method.setRequestHeader("Content-Type", "text/xml; charset=utf-8");
        method.setRequestHeader("Translate", "f");
        method.setRequestHeader("Accept-Encoding", "gzip");

        BufferedReader reader = null;
        try {
            DavGatewayHttpClientFacade.executeGetMethod(httpClient, method, true);
            InputStreamReader inputStreamReader;
            if (isGzipEncoded(method)) {
                inputStreamReader = new InputStreamReader(new GZIPInputStream(method.getResponseBodyAsStream()));
            } else {
                inputStreamReader = new InputStreamReader(method.getResponseBodyAsStream());
            }
            reader = new BufferedReader(inputStreamReader) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        method.releaseConnection();
                    }
                }
            };

        } catch (HttpException e) {
            method.releaseConnection();
            LOGGER.warn("Unable to retrieve message at: " + message.messageUrl);
            if (Settings.getBooleanProperty("davmail.deleteBroken")
                    && method.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                LOGGER.warn("Deleting broken message at: " + message.messageUrl + " permanentUrl: " + message.permanentUrl);
                try {
                    message.delete();
                } catch (IOException ioe) {
                    LOGGER.warn("Unable to delete broken message at: " + message.permanentUrl);
                }
            }
            throw e;
        }
        return reader;
    }

    /**
     * @inheritDoc
     */
    @Override
    public void copyMessage(Message message, String targetFolder) throws IOException {
        String targetPath = URIUtil.encodePath(getFolderPath(targetFolder)) + '/' + UUID.randomUUID().toString();
        CopyMethod method = new CopyMethod(message.permanentUrl, targetPath, false);
        // allow rename if a message with the same name exists
        method.addRequestHeader("Allow-Rename", "t");
        try {
            int statusCode = httpClient.executeMethod(method);
            if (statusCode == HttpStatus.SC_PRECONDITION_FAILED) {
                throw new DavMailException("EXCEPTION_UNABLE_TO_COPY_MESSAGE");
            } else if (statusCode != HttpStatus.SC_CREATED) {
                throw DavGatewayHttpClientFacade.buildHttpException(method);
            }
        } finally {
            method.releaseConnection();
        }
    }

    @Override
    protected void moveToTrash(Message message) throws IOException {
        String destination = URIUtil.encodePath(deleteditemsUrl) + '/' + UUID.randomUUID().toString();
        LOGGER.debug("Deleting : " + message.permanentUrl + " to " + destination);
        MoveMethod method = new MoveMethod(message.permanentUrl, destination, false);
        method.addRequestHeader("Allow-rename", "t");

        int status = DavGatewayHttpClientFacade.executeHttpMethod(httpClient, method);
        // do not throw error if already deleted
        if (status != HttpStatus.SC_CREATED && status != HttpStatus.SC_NOT_FOUND) {
            throw DavGatewayHttpClientFacade.buildHttpException(method);
        }
        if (method.getResponseHeader("Location") != null) {
            destination = method.getResponseHeader("Location").getValue();
        }

        LOGGER.debug("Deleted to :" + destination);
    }
}
