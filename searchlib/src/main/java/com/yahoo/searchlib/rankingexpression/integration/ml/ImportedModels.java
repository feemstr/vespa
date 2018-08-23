// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchlib.rankingexpression.integration.ml;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.yahoo.path.Path;

import java.io.File;
import java.util.Optional;

/**
 * All models imported from the models/ directory in the application package
 *
 * @author bratseth
 */
public class ImportedModels {

    /** All imported models, indexed by their names */
    private final ImmutableMap<String, ImportedModel> importedModels;

    private static final ImmutableList<ModelImporter> importers =
            ImmutableList.of(new TensorFlowImporter(), new OnnxImporter(), new XGBoostImporter());

    /** Create a null imported models */
    public ImportedModels() {
        importedModels = ImmutableMap.of();
    }

    public ImportedModels(File modelsDirectory) {
        ImmutableMap.Builder<String, ImportedModel> builder = new ImmutableMap.Builder<>();

        // Find all subdirectories recursively which contains a model we can read
        importRecursively(modelsDirectory, builder);
        importedModels = builder.build();
    }

    private static void importRecursively(File dir, ImmutableMap.Builder<String, ImportedModel> builder) {
        if ( ! dir.isDirectory()) return;
        for (File child : dir.listFiles()) {
            Optional<ModelImporter> importer = findImporterOf(child);
            if (importer.isPresent()) {
                String name = toName(child);
                builder.put(name, importer.get().importModel(name, child));
            }
            else {
                importRecursively(child, builder);
            }
        }
    }

    private static Optional<ModelImporter> findImporterOf(File path) {
        return importers.stream().filter(item -> item.canImport(path.toString())).findFirst();
    }

    /**
     * Returns the model at the given location in the application package (lazily loaded),
     *
     * @param modelPath the full path to this model (file or directory, depending on model type)
     *                  under the application package
     * @throws IllegalArgumentException if the model cannot be loaded
     */
    public ImportedModel get(File modelPath) {
        return importedModels.get(toName(modelPath));
    }

    private static String toName(File modelPath) {
        Path localPath = Path.fromString(modelPath.toString()).getChildPath();
        return localPath.toString().replace("/", "_").replace('.', '_');
    }

}
