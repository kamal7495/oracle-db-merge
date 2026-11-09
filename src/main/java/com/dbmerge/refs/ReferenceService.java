package com.dbmerge.refs;

import com.dbmerge.conn.ConnectionSource;
import com.dbmerge.ddl.SchemaExtractor;
import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import com.dbmerge.refs.RefModel.Candidate;
import com.dbmerge.refs.RefModel.ReferenceReport;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Extracts one schema, detects undeclared references and optionally checks them against the data. Read-only. */
public class ReferenceService {

    private final SchemaExtractor extractor;
    private final ReferenceDetector detector = new ReferenceDetector();
    private final ReferenceVerifier verifier;

    public ReferenceService(ConnectionSource connections, SchemaExtractor extractor) {
        this.extractor = extractor;
        this.verifier = new ReferenceVerifier(connections);
    }

    public ReferenceReport run(String nickname, boolean checkData, int timeoutSeconds, int maxChecks) throws SQLException {
        SchemaModel model = extractor.extract(nickname, false);
        return analyse(nickname, model, checkData, timeoutSeconds, maxChecks);
    }

    public ReferenceReport analyse(String nickname, SchemaModel model, boolean checkData, int timeoutSeconds, int maxChecks) throws SQLException {
        List<Candidate> candidates = detector.detect(model);
        List<String> warnings = new ArrayList<>(model.warnings());
        if (checkData) {
            ReferenceVerifier.Outcome o = verifier.verify(nickname, candidates, timeoutSeconds, maxChecks);
            candidates = o.candidates();
            warnings.addAll(o.warnings());
        }
        int fks = 0;
        Set<String> fkCols = new HashSet<>();
        for (TableDef t : model.tables().values()) {
            for (ConstraintDef c : t.constraints()) {
                if (!c.type().equals("R")) continue;
                fks++;
                for (String col : c.columns()) fkCols.add(t.name() + "." + col);
            }
        }
        return new ReferenceReport(UUID.randomUUID().toString().replace("-", "").substring(0, 8), Instant.now(), nickname,
                model.owner(), model.tables().size(), fks, fkCols.size(), checkData, candidates, warnings);
    }
}
