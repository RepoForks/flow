package com.keenant.flow;

import com.keenant.flow.exception.DatabaseException;

import java.sql.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * An eager cursor that works on databases that don't support scroll insensitivity (SQLite).
 */
public class SafeEagerCursor extends EagerCursor {
    private final Statement statement;
    private final ResultSet resultSet;
    private ResultSetMetaData metaData;

    private List<Object[]> records;
    private Map<String, Integer> labels;
    private int current;

    public SafeEagerCursor(PreparedStatement statement, ResultSet resultSet) {
        super(statement, resultSet);
        this.statement = statement;
        this.resultSet = resultSet;
    }
    
    private ResultSetMetaData getMetaData() {
        if (metaData == null) {
            try {
                metaData = resultSet.getMetaData();
            } catch (SQLException e) {
                throw new DatabaseException(e);
            }
        }
        return metaData;
    }

    void populateAndClose() {
        if (records == null || labels == null) {
            Map<String, Integer> labels = new HashMap<>();
            List<Object[]> records = new ArrayList<>();

            try {
                // Labels
                for (int i = 1; i <= getMetaData().getColumnCount(); i++) {
                    labels.put(getMetaData().getColumnLabel(i), i);
                }

                // Records
                while (resultSet.next()) {
                    Object[] record = new Object[getMetaData().getColumnCount()];
                    for (int i = 0; i < getMetaData().getColumnCount(); i++) {
                        record[i] = resultSet.getObject(i + 1);
                    }
                    records.add(record);
                }
            } catch (SQLException e) {
                throw new DatabaseException(e);
            }

            this.records = records;
            this.labels = labels;
            close();
        }
    }

    private boolean hasRecord(int record) {
        return record >= 1 && record <= records.size();
    }

    private Object[] getCurrentRecord() {
        return records.get(current - 1);
    }

    @Override
    public void moveTo(int record) throws NoSuchElementException {
        if (!hasRecord(record)) {
            throw new NoSuchElementException();
        }
        current = record;
    }

    @Override
    public void moveToFirst() throws NoSuchElementException {
        if (!hasRecord(1)) {
            throw new NoSuchElementException();
        }
        current = 1;
    }

    @Override
    public void moveToLast() throws NoSuchElementException {
        if (!hasRecord(1)) {
            throw new NoSuchElementException();
        }
        current = records.size();
    }

    @Override
    public EagerCursor move(int record) throws NoSuchElementException {
        moveTo(record);
        return this;
    }

    @Override
    public EagerCursor first() throws NoSuchElementException {
        moveToFirst();
        return this;
    }

    @Override
    public EagerCursor last() throws NoSuchElementException {
        moveToLast();
        return this;
    }

    @Override
    public boolean hasField(int index) {
        return labels.containsValue(index);
    }

    @Override
    public boolean hasField(String label) throws IllegalArgumentException {
        return labels.containsKey(label);
    }

    @Override
    public int getFieldIndex(String label) throws IllegalArgumentException, NoSuchElementException {
        return labels.get(label);
    }

    @Override
    public String getFieldLabel(int index) throws NoSuchElementException {
        for (String label : labels.keySet()) {
            int current = labels.get(label);
            if (current == index) {
                return label;
            }
        }
        throw new NoSuchElementException();
    }

    @Override
    public Optional<Object> get(int index) throws NoSuchElementException {
        return Optional.ofNullable(getCurrentRecord()[index - 1]);
    }

    @Override
    public boolean moveNext() {
        current++;
        return hasRecord(current);
    }

    @Override
    public Cursor next() throws NoSuchElementException {
        if (!moveNext()) {
            throw new NoSuchElementException();
        }
        return this;
    }

    @Override
    public Stream<Cursor> stream() {
        Stream<Cursor> stream = StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED),false);
        stream = stream.onClose(this::close);
        return stream;
    }

    @Override
    public Iterator<Cursor> iterator() {
        return new CursorIterator();
    }

    @Override
    public void close() {
        try {
            statement.close();
            resultSet.close();
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
    }

    private class CursorIterator implements Iterator<Cursor> {
        @Override
        public boolean hasNext() {
            return hasRecord(current + 1);
        }

        @Override
        public Cursor next() {
            return SafeEagerCursor.this.next();
        }
    }
}