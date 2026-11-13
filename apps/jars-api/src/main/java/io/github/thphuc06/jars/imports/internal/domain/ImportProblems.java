package io.github.thphuc06.jars.imports.internal.domain;

import io.github.thphuc06.jars.shared.ProblemType;

/** Problem types of the import API. */
public final class ImportProblems {

    public static final ProblemType INVALID_IMPORT = new ProblemType("invalid-import", 422, "Tệp sao kê không hợp lệ");

    public static final ProblemType IMPORT_ALREADY_STARTED =
            new ProblemType("import-already-started", 409, "Phiên nhập sao kê đã nhận tệp");

    private ImportProblems() {}
}
