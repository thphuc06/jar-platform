package io.github.thphuc06.jars.imports.internal.batch;

import io.github.thphuc06.jars.imports.internal.domain.ImportStatus;
import io.github.thphuc06.jars.imports.internal.domain.StatementImport;
import io.github.thphuc06.jars.imports.internal.parsing.CsvRecords;
import io.github.thphuc06.jars.imports.internal.parsing.InvalidStatementException;
import io.github.thphuc06.jars.imports.internal.parsing.StatementColumns;
import io.github.thphuc06.jars.imports.internal.persistence.StatementImportRepository;
import io.github.thphuc06.jars.imports.internal.storage.StatementStore;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

/**
 * Reads the statement file of one import. The whole file is read at {@link #open} (at most a few MB): its SHA-256 is
 * the identity of the file (same file twice = rejected; idempotency keys of its rows), its header gives the columns,
 * and the position is kept in the step's {@link ExecutionContext} so that a restarted import resumes after the last
 * committed chunk instead of at the first row.
 */
class StatementReader implements ItemStreamReader<ImportRow.Raw> {

    static final String POSITION = "statement.position";
    static final String SHA256 = "statement.sha256";
    static final String ROWS = "statement.rows";

    private final StatementImport statementImport;
    private final StatementStore store;
    private final StatementImportRepository imports;
    private final ImportContext context;

    private List<CsvRecords.Record> rows = List.of();
    private StatementColumns columns;
    private int position;

    StatementReader(
            StatementImport statementImport,
            StatementStore store,
            StatementImportRepository imports,
            ImportContext context) {
        this.statementImport = statementImport;
        this.store = store;
        this.imports = imports;
        this.context = context;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        byte[] content = store.read(statementImport.getObjectKey());
        String sha256 = sha256(content);
        if (imports.existsByUserIdAndFileSha256AndStatusAndIdNot(
                statementImport.getUserId(), sha256, ImportStatus.COMPLETED, statementImport.getId())) {
            throw new InvalidStatementException(
                    "Tệp này đã được nhập trước đó. Nhập lại sẽ ghi trùng các giao dịch, nên đã bị bỏ qua.");
        }
        String text = decode(content);
        List<CsvRecords.Record> records = CsvRecords.parse(text, CsvRecords.delimiterOf(text));
        if (records.isEmpty()) {
            throw new InvalidStatementException("Tệp trống.");
        }
        columns = StatementColumns.detect(records.getFirst().fields());
        rows = records.subList(1, records.size());
        position = executionContext.getInt(POSITION, 0);

        context.fileRead(sha256);
        executionContext.putString(SHA256, sha256);
        executionContext.putInt(ROWS, rows.size());
    }

    @Override
    public ImportRow.Raw read() {
        if (position >= rows.size()) {
            return null;
        }
        return new ImportRow.Raw(rows.get(position++), columns);
    }

    @Override
    public void update(ExecutionContext executionContext) {
        executionContext.putInt(POSITION, position);
    }

    /** UTF-8 (with or without BOM); a file in another encoding is rejected instead of being read as garbage. */
    private static String decode(byte[] content) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InvalidStatementException(
                    "Tệp không phải UTF-8. Hãy xuất sao kê dạng CSV UTF-8 rồi tải lên lại.");
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
