// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespaxmlparser;

import com.yahoo.document.Document;
import com.yahoo.document.DocumentId;
import com.yahoo.document.DocumentTypeManager;
import com.yahoo.document.DocumentUpdate;
import com.yahoo.document.TestAndSetCondition;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * XML parser for Vespa document XML.
 *
 * Parses an entire document "feed", which consists of a vespafeed element containing
 * zero or more instances of documents, updates or removes.
 *
 * Standard usage is to create an Operation object and call read(Operation) until
 * operation.getType() returns OperationType.INVALID.
 *
 * If you are looking to parse only a single document or update, use VespaXMLDocumentReader
 * or VespaXMLUpdateReader respectively.
 */
public class VespaXMLFeedReader extends VespaXMLReader implements FeedReader {

    /**
     * Creates a reader that reads from the given file.
     */
    public VespaXMLFeedReader(String fileName, DocumentTypeManager docTypeManager) throws Exception {
        super(fileName, docTypeManager);
        readInitial();
    }

    /**
     * Creates a reader that reads from the given stream.
     */
    public VespaXMLFeedReader(InputStream stream, DocumentTypeManager docTypeManager) throws Exception {
        super(stream, docTypeManager);
        readInitial();
    }

    /**
     * Creates a reader that uses the given reader to read - this can be used if the vespa feed
     * is part of a larger XML document.
     */
    public VespaXMLFeedReader(XMLStreamReader reader, DocumentTypeManager manager) throws Exception {
        super(reader, manager);
        readInitial();
    }

    /**
     * Skips the initial "vespafeed" tag.
     */
    private void readInitial() throws Exception {
        boolean found = false;

        while (reader.hasNext()) {
            int type = reader.next();
            if (type == XMLStreamReader.START_ELEMENT) {
                if ("vespafeed".equals(reader.getName().toString())) {
                    found = true;
                    break;
                }
            }
        }

        if (!found) {
            throw newDeserializeException("Feed information must be contained within a \"vespafeed\" element");
        }
    }

    public enum OperationType {
        DOCUMENT,
        REMOVE,
        UPDATE,
        INVALID
    }

    /**
     * Represents a feed operation found by the parser. Can be one of the following types:
     * - getType() == DOCUMENT: getDocument() is valid.
     * - getType() == REMOVE: getRemove() is valid.
     * - getType() == UPDATE: getUpdate() is valid.
     */
    public static class Operation {

        private OperationType type;
        private Document doc;
        private DocumentId remove;
        private DocumentUpdate docUpdate;
        private TestAndSetCondition condition = TestAndSetCondition.NOT_PRESENT_CONDITION;

        public Operation() {
            setInvalid();
        }

        public void setInvalid() {
            type = OperationType.INVALID;
            doc = null;
            remove = null;
            docUpdate = null;
            condition = TestAndSetCondition.NOT_PRESENT_CONDITION;
        }

        public OperationType getType() {
            return type;
        }

        public Document getDocument() {
            return doc;
        }

        public void setDocument(Document doc) {
            this.type = OperationType.DOCUMENT;
            this.doc = doc;
        }

        public DocumentId getRemove() {
            return remove;
        }

        public void setRemove(DocumentId remove) {
            this.type = OperationType.REMOVE;
            this.remove = remove;
        }

        public DocumentUpdate getDocumentUpdate() {
            return docUpdate;
        }

        public void setDocumentUpdate(DocumentUpdate docUpdate) {
            this.type = OperationType.UPDATE;
            this.docUpdate = docUpdate;
        }

        public void setCondition(TestAndSetCondition condition) {
            this.condition = condition;
        }

        public TestAndSetCondition getCondition() {
            return condition;
        }

        @Override
        public String toString() {
            return "Operation{" +
                   "type=" + type +
                   ", doc=" + doc +
                   ", remove=" + remove +
                   ", docUpdate=" + docUpdate +
                   '}';
        }
    }

    /**
     * <p>Reads all operations from the XML stream and puts into a list. Note
     * that if the XML stream is large, this may cause out of memory errors, so
     * make sure to use this only with small streams.</p>
     *
     * @return The list of all read operations.
     */
    public List<Operation> readAll() throws Exception {
        List<Operation> list = new ArrayList<>();
        while (true) {
            Operation op = new Operation();
            read(op);
            if (op.getType() == OperationType.INVALID) {
                return list;
            } else {
                list.add(op);
            }
        }
    }

    /* (non-Javadoc)
     * @see com.yahoo.vespaxmlparser.FeedReader#read(com.yahoo.vespaxmlparser.VespaXMLFeedReader.Operation)
     */
    @Override
    public void read(Operation operation) throws Exception {
        String startTag = null;
        operation.setInvalid();

        try {
            while (reader.hasNext()) {
                int type = reader.next();

                if (type == XMLStreamReader.START_ELEMENT) {
                    startTag = reader.getName().toString();

                    if ("document".equals(startTag)) {
                        VespaXMLDocumentReader documentReader = new VespaXMLDocumentReader(reader, docTypeManager);
                        Document document = new Document(documentReader);
                        operation.setDocument(document);
                        operation.setCondition(TestAndSetCondition.fromConditionString(documentReader.getCondition()));
                        return;
                    } else if ("update".equals(startTag)) {
                        VespaXMLUpdateReader updateReader = new VespaXMLUpdateReader(reader, docTypeManager);
                        DocumentUpdate update = new DocumentUpdate(updateReader);
                        operation.setDocumentUpdate(update);
                        operation.setCondition(TestAndSetCondition.fromConditionString(updateReader.getCondition()));
                        return;
                    } else if ("remove".equals(startTag)) {
                        boolean documentIdFound = false;

                        Optional<String> condition = Optional.empty();
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            final String attributeName = reader.getAttributeName(i).toString();
                            if ("documentid".equals(attributeName) || "id".equals(attributeName)) {
                                operation.setRemove(new DocumentId(reader.getAttributeValue(i)));
                                documentIdFound = true;
                            } else if ("condition".equals(attributeName)) {
                                condition = Optional.of(reader.getAttributeValue(i));
                            }
                        }

                        if (!documentIdFound) {
                            throw newDeserializeException("Missing \"documentid\" attribute for remove operation");
                        }

                        operation.setCondition(TestAndSetCondition.fromConditionString(condition));

                        return;
                    } else {
                        throw newDeserializeException("Element \"" + startTag + "\" not allowed in this context");
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw(e);
            // Skip to end of current tag with other exceptions.
        } catch (Exception e) {
            try {
                if (startTag != null) {
                    skipToEnd(startTag);
                }
            } catch (Exception ignore) {
            }

            throw(e);
        }
    }

}
