/*
 * Copyright 2016 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.realm.internal.fields;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.realm.RealmFieldType;
import io.realm.internal.ColumnInfo;
import io.realm.internal.Table;


/**
 * Class describing a single field, possibly several links away.
 */
public abstract class FieldDescriptor {
    public interface SchemaProxy {
        boolean hasCache();

        ColumnInfo getColumnInfo(String tableName);

        long getNativeTablePtr(String targetTable);
    }

    public static final Set<RealmFieldType> ALL_LINK_FIELD_TYPES;

    static {
        Set<RealmFieldType> s = new HashSet<>(3);
        s.add(RealmFieldType.OBJECT);
        s.add(RealmFieldType.LIST);
        s.add(RealmFieldType.LINKING_OBJECTS);
        ALL_LINK_FIELD_TYPES = Collections.unmodifiableSet(s);
    }

    public static final Set<RealmFieldType> SIMPLE_LINK_FIELD_TYPES;

    static {
        Set<RealmFieldType> s = new HashSet<>(2);
        s.add(RealmFieldType.OBJECT);
        s.add(RealmFieldType.LIST);
        SIMPLE_LINK_FIELD_TYPES = Collections.unmodifiableSet(s);
    }

    public static final Set<RealmFieldType> LIST_LINK_FIELD_TYPE;

    static {
        Set<RealmFieldType> s = new HashSet<>(1);
        s.add(RealmFieldType.LIST);
        LIST_LINK_FIELD_TYPE = Collections.unmodifiableSet(s);
    }

    public static final Set<RealmFieldType> OBJECT_LINK_FIELD_TYPE;

    static {
        Set<RealmFieldType> s = new HashSet<>(1);
        s.add(RealmFieldType.OBJECT);
        OBJECT_LINK_FIELD_TYPE = Collections.unmodifiableSet(s);
    }

    public static final Set<RealmFieldType> NO_LINK_FIELD_TYPE = Collections.emptySet();

    public static FieldDescriptor createFieldDescriptor(
            Table table,
            String fieldDescription,
            Set<RealmFieldType> validInternalColumnTypes) {
        return new DynamicFieldDescriptor(table, fieldDescription, validInternalColumnTypes, null);
    }

    /**
     * TODO:
     * I suspect that choosing the parsing strategy based on whether there is a ref to a ColumnIndices
     * around or not, is bad architecture.  Almost certainly, there should be a schema that has
     * ColumnIndices and one that does not and the strategies below should belong to the first
     * and second, respectively.  --gbm
     */
    public static FieldDescriptor createFieldDescriptor(
            SchemaProxy schema,
            Table table,
            String fieldDescription,
            RealmFieldType... validFinalColumnTypes) {
        Set<RealmFieldType> columnTypes = new HashSet<>(Arrays.asList(validFinalColumnTypes));
        return (!schema.hasCache())
                ? new DynamicFieldDescriptor(table, fieldDescription, SIMPLE_LINK_FIELD_TYPES, columnTypes)
                : new CachedFieldDescriptor(schema, table.getClassName(), fieldDescription, ALL_LINK_FIELD_TYPES, columnTypes);
    }


    private final List<String> fields;
    private final Set<RealmFieldType> validInternalColumnTypes;
    private final Set<RealmFieldType> validFinalColumnTypes;

    private String finalColumnName;
    private RealmFieldType finalColumnType;
    private long[] columnIndices;
    private long[] tableNativePointers;

    /**
     * @param fieldDescription fieldName or link path to a field name.
     * @param validInternalColumnTypes valid internal link types.
     * @param validFinalColumnTypes valid field types for the last field in a linked field
     */
    protected FieldDescriptor(
            String fieldDescription, Set<RealmFieldType>
            validInternalColumnTypes,
            Set<RealmFieldType> validFinalColumnTypes) {
        this.fields = parseFieldDescription(fieldDescription);
        int nFields = fields.size();
        if (nFields <= 0) {
            throw new IllegalArgumentException("Invalid query: Empty field descriptor");
        }
        this.validInternalColumnTypes = validInternalColumnTypes;
        this.validFinalColumnTypes = validFinalColumnTypes;
    }

    /**
     * The number of columnNames in the field description
     *
     * @return the number of fields.
     */
    public final int length() {
        return fields.size();
    }

    /**
     * After the field description (@see parseFieldDescription(String) is parsed, this method
     * returns a java array of column indices for the columns named in the description.
     * If the column is a LinkingObjects column, the index is the index in the <b>source</b> table.
     */
    public final long[] getColumnIndices() {
        compileIfNecessary();
        return Arrays.copyOf(columnIndices, columnIndices.length);
    }

    /**
     * After the field description (@see parseFieldDescription(String) is parsed, this method
     * returns a java array.  For most columns the table will be the 'current' table, so this
     * array will contain ativeObject.NULLPTR.  If a column is a LinkingObjects column, however,
     * the array contains the native pointer to the <b>source</b> table.
     */
    public final long[] getNativeTablePointers() {
        compileIfNecessary();
        return Arrays.copyOf(tableNativePointers, tableNativePointers.length);
    }

    /**
     * Getter for the name of the final column in the descriptor.
     *
     * @return the name of the final column
     */
    public String getFinalColumnName() {
        compileIfNecessary();
        return finalColumnName;
    }

    /**
     * Getter for the type of the final column in the descriptor.
     *
     * @return the type of the final column
     */
    public RealmFieldType getFinalColumnType() {
        compileIfNecessary();
        return finalColumnType;
    }

    /**
     * Subclasses implement this method with a compilation strategy.
     */
    protected abstract void compileFieldDescription(List<String> fields);

    /**
     * Verify that the named link column, in the named table, of the specified type, is one of the legal internal column types.
     *
     * @param tableName Name of the table containing the column: used in error messages
     * @param columnName Name of the column whose type is being tested: used in error messages
     * @param columnType The type of the column: examined for validity.
     */
    protected final void verifyInternalColumnType(String tableName, String columnName, RealmFieldType columnType) {
        verifyColumnType(tableName, columnName, columnType, validInternalColumnTypes);
    }

    /**
     * Store the results of compiling the field description.
     * Subclasses call this as the last action in
     *
     * @param finalClassName the name of the final table in the field description.
     * @param finalColumnName the name of the final column in the field description.
     * @param finalColumnType the type of the final column in the field description: MAY NOT BE {@code null}!
     * @param columnIndices the array of columnIndices.
     * @param tableNativePointers the array of table pointers
     */
    protected final void setCompilationResults(
            String finalClassName,
            String finalColumnName,
            RealmFieldType finalColumnType,
            long[] columnIndices,
            long[] tableNativePointers) {
        if ((validFinalColumnTypes != null) && (validFinalColumnTypes.size() > 0)) {
            verifyColumnType(finalClassName, finalColumnName, finalColumnType, validFinalColumnTypes);
        }
        this.finalColumnName = finalColumnName;
        this.finalColumnType = finalColumnType;
        this.columnIndices = columnIndices;
        this.tableNativePointers = tableNativePointers;
    }

    /**
     * Parse the passed field description into its components.
     * This must be standard across implementations and is, therefore, implemented in the base class.
     *
     * @param fieldDescription a field description.
     * @return the parse tree: a list of column names
     */
    private List<String> parseFieldDescription(String fieldDescription) {
        if (fieldDescription == null || fieldDescription.equals("")) {
            throw new IllegalArgumentException("Invalid query: field name is empty");
        }
        if (fieldDescription.endsWith(".")) {
            throw new IllegalArgumentException("Invalid query: field name must not end with a period ('.')");
        }
        return Arrays.asList(fieldDescription.split("\\."));
    }

    private void verifyColumnType(String tableName, String columnName, RealmFieldType columnType, Set<RealmFieldType> validTypes) {
        if (!validTypes.contains(columnType)) {
            throw new IllegalArgumentException(String.format(
                    "Invalid query: field '%s' in table '%s' is of invalid type '%s'.",
                    columnName, tableName, columnType.toString()));
        }
    }

    private void compileIfNecessary() {
        if (finalColumnType == null) {
            compileFieldDescription(fields);
        }
    }
}
