/*
 * Copyright 2026 bentzn
 * SPDX-License-Identifier: Apache-2.0
 */
package com.bentzn.raposza.net;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads the Splice release-notes feed into claims.
 *
 * The feed is Atom. Each entry is one release: an id ending in the tag name, a
 * title, a timestamp and the notes as html. It carries the one thing the tags
 * endpoint does not - A TIME - and lacks the two the tags endpoint has, the
 * commit sha and the whole history. It holds a rolling window of the newest
 * entries only, so it is a complement to that source and never a replacement,
 * and an entry leaving the window says nothing about the release.
 *
 * THE TIME IS THE TAGGED COMMIT'S, NOT THE MOMENT THE RELEASE BECAME
 * AVAILABLE. Measured on 0.8.2: the feed stamps 16:29:55Z and the tag became
 * visible on the tags endpoint at 17:15:52Z, 46 minutes later, because the ref
 * appears when the release build finishes. It is therefore claimed as
 * commit_time and under no other name; when a consumer could first have
 * obtained the release is firstObservedAt on the event.
 *
 * Identity is the tag name, taken from the entry id after the last slash, which
 * is what joins these claims to the ones the tags endpoint gives. The version
 * is read from that same name by the rule the tags normalizer uses, so a feed
 * entry that arrives before the tag is visible still states a version.
 *
 * The parser is configured with external entity resolution and doctypes
 * refused: the body is retrieved over the network from a third party, and an
 * XML parser that resolves what a body tells it to is a way into the host.
 *
 * A body that is not an Atom feed with entries is refused whole.
 *
 * Author Claude/bentzn
 */
public final class SpliceReleaseNotesNormalizer implements Normalizer {

    /** The identifier and version of what this class produces. */
    public static final String ID = "SpliceReleaseNotesNormalizer@1";

    /** The source this reads. */
    public static final String SOURCE_ID = "splice-release-notes";

    private static final String NS_ATOM = "http://www.w3.org/2005/Atom";

    private static final String KIND = "SOFTWARE_RELEASE";

    private static final String TYPE_NOTE = "release-note";

    private static final String HIGH = "HIGH";

    private static final String REVIEW = "REQUIRES_REVIEW";

    private static final Pattern PAT_PATCH = Pattern.compile("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)");

    private static final Pattern PAT_MINOR = Pattern.compile("v?([0-9]+)\\.([0-9]+)");


    @Override
    public String id() {
        return ID;
    }


    @Override
    public String sourceId() {
        return SOURCE_ID;
    }


    @Override
    public List<Claim> normalize(byte[] bytesBody) throws Refused {
        Element elemFeed = feed(bytesBody);
        NodeList lstEntry = elemFeed.getElementsByTagNameNS(NS_ATOM, "entry");
        if (lstEntry.getLength() == 0)
            throw new Refused("the feed carries no entry");
        Set<String> setRef = new HashSet<>();
        List<Claim> lstOut = new ArrayList<>();
        for (int posEntry = 0; posEntry < lstEntry.getLength(); posEntry++) {
            lstOut.addAll(entry((Element) lstEntry.item(posEntry), posEntry, setRef));
        }
        lstOut.sort(Comparator.comparing(Claim::subjectRef).thenComparing(Claim::field));
        return lstOut;
    }


    private static Element feed(byte[] bytesBody) throws Refused {
        try {
            DocumentBuilderFactory facBuilder = DocumentBuilderFactory.newInstance();
            facBuilder.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            facBuilder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            facBuilder.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            facBuilder.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            facBuilder.setExpandEntityReferences(false);
            facBuilder.setNamespaceAware(true);
            DocumentBuilder bldDoc = facBuilder.newDocumentBuilder();
            bldDoc.setEntityResolver((idPublic, idSystem) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
            Element elemRoot = bldDoc.parse(new ByteArrayInputStream(bytesBody)).getDocumentElement();
            if (elemRoot == null || !"feed".equals(elemRoot.getLocalName())
                    || !NS_ATOM.equals(elemRoot.getNamespaceURI()))
                throw new Refused("the body is not an Atom feed");
            return elemRoot;
        }
        catch (ParserConfigurationException | org.xml.sax.SAXException | java.io.IOException e) {
            throw new Refused("the body is not parseable XML: " + e.getMessage(), e);
        }
    }


    private static List<Claim> entry(Element elemEntry, int posEntry, Set<String> setRef) throws Refused {
        String idEntry = child(elemEntry, "id");
        if (idEntry == null)
            throw new Refused("entry " + posEntry + " has no id");
        int posSlash = idEntry.lastIndexOf('/');
        String nameTag = posSlash < 0 ? null : idEntry.substring(posSlash + 1);
        if (nameTag == null || nameTag.isEmpty())
            throw new Refused("entry " + posEntry + ": no tag name in id " + idEntry);
        if (!setRef.add(nameTag))
            throw new Refused("entry " + posEntry + ": tag " + nameTag + " repeats");
        String stampRaw = child(elemEntry, "updated");
        if (stampRaw == null)
            throw new Refused("entry " + posEntry + " (" + nameTag + ") has no updated stamp");
        String stampUtc;
        try {
            stampUtc = Dataset.iso(OffsetDateTime.parse(stampRaw).toInstant());
        }
        catch (DateTimeParseException e) {
            throw new Refused("entry " + posEntry + " (" + nameTag + "): unreadable stamp " + stampRaw, e);
        }

        List<Claim> lstOut = new ArrayList<>();
        lstOut.add(claim(nameTag, "upstream.type", TYPE_NOTE, null, TYPE_NOTE, HIGH));
        lstOut.add(claim(nameTag, "commit_time", stampUtc, "TIMESTAMP", stampRaw, HIGH));
        String title = child(elemEntry, "title");
        if (title != null) {
            lstOut.add(claim(nameTag, "title", title, null, title, HIGH));
        }
        String notes = child(elemEntry, "content");
        if (notes != null) {
            lstOut.add(claim(nameTag, "description", notes, null, notes, HIGH));
        }
        Matcher matPatch = PAT_PATCH.matcher(nameTag);
        Matcher matMinor = PAT_MINOR.matcher(nameTag);
        if (matPatch.matches()) {
            lstOut.add(claim(nameTag, "version",
                    matPatch.group(1) + "." + matPatch.group(2) + "." + matPatch.group(3), "PATCH",
                    nameTag, HIGH));
        }
        else if (matMinor.matches()) {
            lstOut.add(claim(nameTag, "version", matMinor.group(1) + "." + matMinor.group(2), "MINOR",
                    nameTag, HIGH));
        }
        else {
            lstOut.add(claim(nameTag, "version", null, null, nameTag, REVIEW));
        }
        return lstOut;
    }


    /**
     * @param elemEntry the entry
     * @param nameChild the Atom element wanted
     * @return its text, or null when the entry does not carry it directly
     */
    private static String child(Element elemEntry, String nameChild) {
        NodeList lstChild = elemEntry.getChildNodes();
        for (int posChild = 0; posChild < lstChild.getLength(); posChild++) {
            Node nodeOne = lstChild.item(posChild);
            if (nodeOne.getNodeType() == Node.ELEMENT_NODE && NS_ATOM.equals(nodeOne.getNamespaceURI())
                    && nameChild.equals(nodeOne.getLocalName())) {
                String textOne = nodeOne.getTextContent();
                return textOne == null || textOne.isEmpty() ? null : textOne;
            }
        }
        return null;
    }


    private static Claim claim(String nameTag, String field, String value, String precision, String raw,
            String confidence) {
        return new Claim(nameTag, null, KIND, null, field, value, precision, raw, confidence);
    }
}
