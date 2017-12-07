/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.tasks;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import groovy.lang.GString;
import org.gradle.api.Describable;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NonNullApi;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.TaskInputsInternal;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.file.CompositeFileCollection;
import org.gradle.api.internal.file.collections.FileCollectionResolveContext;
import org.gradle.api.internal.tasks.properties.PropertiesWalker;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.tasks.TaskInputPropertyBuilder;
import org.gradle.api.tasks.TaskInputs;
import org.gradle.internal.typeconversion.UnsupportedNotationException;
import org.gradle.util.DeprecationLogger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.gradle.api.internal.tasks.TaskPropertyUtils.ensurePropertiesHaveNames;
import static org.gradle.util.GUtil.uncheckedCall;

@NonNullApi
public class DefaultTaskInputs implements TaskInputsInternal {
    private final FileCollection allInputFiles;
    private final FileCollection allSourceFiles;
    private final TaskInternal task;
    private final TaskMutator taskMutator;
    private final PropertiesWalker propertiesWalker;
    private final List<DeclaredTaskInputProperty> runtimeProperties = Lists.newArrayList();
    private final List<DeclaredTaskInputFileProperty> runtimeFileProperties = Lists.newArrayList();
    private final TaskInputs deprecatedThis;
    private final PropertySpecFactory specFactory;

    public DefaultTaskInputs(TaskInternal task, TaskMutator taskMutator, PropertiesWalker propertiesWalker, PropertySpecFactory specFactory) {
        this.task = task;
        this.taskMutator = taskMutator;
        this.propertiesWalker = propertiesWalker;
        String taskName = task.getName();
        this.allInputFiles = new TaskInputUnionFileCollection(taskName, "input", false, this);
        this.allSourceFiles = new TaskInputUnionFileCollection(taskName, "source", true, this);
        this.deprecatedThis = new LenientTaskInputsDeprecationSupport(this);
        this.specFactory = specFactory;
    }

    @Override
    public boolean getHasInputs() {
        HasInputsVisitor visitor = new HasInputsVisitor();
        visitAllProperties(visitor);
        return visitor.hasInputs();
    }

    private void visitAllProperties(PropertyVisitor visitor) {
        propertiesWalker.visitProperties(specFactory, visitor, task);
        visitRuntimeProperties(visitor);
    }

    @Override
    public void visitRuntimeProperties(PropertyVisitor visitor) {
        ensurePropertiesHaveNames(runtimeFileProperties);
        for (DeclaredTaskInputFileProperty fileProperty : runtimeFileProperties) {
            visitor.visitInputFileProperty(fileProperty);
        }
        for (DeclaredTaskInputProperty inputProperty : runtimeProperties) {
            visitor.visitInputProperty(inputProperty);
        }
    }

    @Override
    public FileCollection getFiles() {
        return allInputFiles;
    }

    @Override
    public ImmutableSortedSet<TaskInputFilePropertySpec> getFileProperties() {
        GetFilePropertiesVisitor visitor = new GetFilePropertiesVisitor();
        visitAllProperties(visitor);
        return visitor.getFileProperties();
    }

    @Override
    public GetFilePropertiesVisitor getFilePropertiesVisitor() {
        return new GetFilePropertiesVisitor();
    }

    @Override
    public TaskInputsInternal.GetInputPropertiesVisitor getInputPropertiesVisitor() {
        return new GetInputPropertiesVisitor();
    }

    @Override
    public TaskInputFilePropertyBuilderInternal files(final Object... paths) {
        return taskMutator.mutate("TaskInputs.files(Object...)", new Callable<TaskInputFilePropertyBuilderInternal>() {
            @Override
            public TaskInputFilePropertyBuilderInternal call() {
                StaticValue value = new StaticValue(unpackVarargs(paths));
                DeclaredTaskInputFileProperty fileSpec = specFactory.createInputFileSpec(value, ValidationActions.NO_OP);
                runtimeFileProperties.add(fileSpec);
                return fileSpec;
            }
        });
    }

    private static Object unpackVarargs(Object[] args) {
        if (args.length == 1) {
            return args[0];
        }
        return args;
    }

    @Override
    public TaskInputFilePropertyBuilderInternal file(final Object path) {
        return taskMutator.mutate("TaskInputs.file(Object)", new Callable<TaskInputFilePropertyBuilderInternal>() {
            @Override
            public TaskInputFilePropertyBuilderInternal call() {
                StaticValue value = new StaticValue(path);
                DeclaredTaskInputFileProperty fileSpec = specFactory.createInputFileSpec(value, RUNTIME_INPUT_FILE_VALIDATOR);
                runtimeFileProperties.add(fileSpec);
                return fileSpec;
            }
        });
    }

    @Override
    public TaskInputFilePropertyBuilderInternal dir(final Object dirPath) {
        return taskMutator.mutate("TaskInputs.dir(Object)", new Callable<TaskInputFilePropertyBuilderInternal>() {
            @Override
            public TaskInputFilePropertyBuilderInternal call() {
                StaticValue value = new StaticValue(dirPath);
                DeclaredTaskInputFileProperty dirSpec = specFactory.createInputDirSpec(value, RUNTIME_INPUT_DIRECTORY_VALIDATOR);
                runtimeFileProperties.add(dirSpec);
                return dirSpec;
            }
        });
    }

    @Override
    public boolean getHasSourceFiles() {
        HasSourceFilesVisitor visitor = new HasSourceFilesVisitor();
        visitAllProperties(visitor);
        return visitor.hasSourceFiles();
    }

    @Override
    public FileCollection getSourceFiles() {
        return allSourceFiles;
    }

    public Map<String, Object> getProperties() {
        GetInputPropertiesVisitor visitor = new GetInputPropertiesVisitor();
        visitAllProperties(visitor);
        return visitor.getProperties();
    }

    @Nullable
    private Object prepareValue(@Nullable Object value) {
        while (true) {
            if (value instanceof Callable) {
                Callable callable = (Callable) value;
                value = uncheckedCall(callable);
            } else if (value instanceof FileCollection) {
                FileCollection fileCollection = (FileCollection) value;
                return fileCollection.getFiles();
            } else {
                return avoidGString(value);
            }
        }
    }

    @Nullable
    private static Object avoidGString(@Nullable Object value) {
        return (value instanceof GString) ? value.toString() : value;
    }

    @Override
    public TaskInputPropertyBuilder property(final String name, @Nullable final Object value) {
        return taskMutator.mutate("TaskInputs.property(String, Object)", new Callable<TaskInputPropertyBuilder>() {
            @Override
            public TaskInputPropertyBuilder call() {
                StaticValue staticValue = new StaticValue(value);
                DefaultTaskInputPropertySpec inputPropertySpec = specFactory.createInputPropertySpec(name, staticValue);
                runtimeProperties.add(inputPropertySpec);
                return inputPropertySpec;
            }
        });
    }

    @Override
    public TaskInputs properties(final Map<String, ?> newProps) {
        taskMutator.mutate("TaskInputs.properties(Map)", new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<String, ?> entry : newProps.entrySet()) {
                    StaticValue staticValue = new StaticValue(entry.getValue());
                    String name = entry.getKey();
                    runtimeProperties.add(specFactory.createInputPropertySpec(name, staticValue));
                }
            }
        });
        return deprecatedThis;
    }

    private static class TaskInputUnionFileCollection extends CompositeFileCollection implements Describable {
        private final boolean skipWhenEmptyOnly;
        private final String taskName;
        private final String type;
        private final DefaultTaskInputs taskInputs;

        public TaskInputUnionFileCollection(String taskName, String type, boolean skipWhenEmptyOnly, DefaultTaskInputs taskInputs) {
            this.taskName = taskName;
            this.type = type;
            this.skipWhenEmptyOnly = skipWhenEmptyOnly;
            this.taskInputs = taskInputs;
        }

        @Override
        public String getDisplayName() {
            return "task '" + taskName + "' " + type + " files";
        }

        @Override
        public void visitContents(final FileCollectionResolveContext context) {
            taskInputs.visitAllProperties(new PropertyVisitor.Adapter() {
                @Override
                public void visitInputFileProperty(DeclaredTaskInputFileProperty fileProperty) {
                    if (!skipWhenEmptyOnly || fileProperty.isSkipWhenEmpty()) {
                        context.add(fileProperty.getPropertyFiles());
                    }
                }
            });
        }
    }

    private static final ValidationAction RUNTIME_INPUT_FILE_VALIDATOR = wrapRuntimeApiValidator("file", ValidationActions.INPUT_FILE_VALIDATOR);

    private static final ValidationAction RUNTIME_INPUT_DIRECTORY_VALIDATOR = wrapRuntimeApiValidator("dir", ValidationActions.INPUT_DIRECTORY_VALIDATOR);

    private static ValidationAction wrapRuntimeApiValidator(final String method, final ValidationAction validator) {
        return new ValidationAction() {
            @Override
            public void validate(String propertyName, Object value, TaskValidationContext context, TaskValidationContext.Severity severity) {
                try {
                    validator.validate(propertyName, value, context, severity);
                } catch (UnsupportedNotationException ex) {
                    DeprecationLogger.nagUserOfDeprecated("Using TaskInputs." + method + "() with something that doesn't resolve to a File object", "Use TaskInputs.files() instead");
                }
            }
        };
    }


    private static class HasInputsVisitor extends PropertyVisitor.Adapter {
        private boolean hasInputs;

        public boolean hasInputs() {
            return hasInputs;
        }

        @Override
        public void visitInputFileProperty(DeclaredTaskInputFileProperty inputFileProperty) {
            hasInputs = true;
        }

        @Override
        public void visitInputProperty(DeclaredTaskInputProperty inputProperty) {
            hasInputs = true;
        }
    }

    private class GetFilePropertiesVisitor extends PropertyVisitor.Adapter implements TaskInputsInternal.GetFilePropertiesVisitor {
        private ImmutableSortedSet.Builder<TaskInputFilePropertySpec> builder = ImmutableSortedSet.naturalOrder();
        private Set<String> names = Sets.newHashSet();
        private boolean hasSourceFiles;

        private ImmutableSortedSet<TaskInputFilePropertySpec> fileProperties;

        @Override
        public void visitInputFileProperty(DeclaredTaskInputFileProperty inputFileProperty) {
            String propertyName = inputFileProperty.getPropertyName();
            if (!names.add(propertyName)) {
                throw new IllegalArgumentException(String.format("Multiple %s file properties with name '%s'", "input", propertyName));
            }
            builder.add(inputFileProperty);
            if (inputFileProperty.isSkipWhenEmpty()) {
                hasSourceFiles = true;
            }
        }

        @Override
        public ImmutableSortedSet<TaskInputFilePropertySpec> getFileProperties() {
            if (fileProperties == null) {
                fileProperties = builder.build();
            }
            return fileProperties;
        }

        @Override
        public FileCollection getFiles() {
            return new CompositeFileCollection() {
                @Override
                public String getDisplayName() {
                    return "task '" + task.getName() + "' input files";
                }

                @Override
                public void visitContents(FileCollectionResolveContext context) {
                    for (TaskInputFilePropertySpec filePropertySpec : getFileProperties()) {
                        context.add(filePropertySpec.getPropertyFiles());
                    }
                }
            };
        }

        @Override
        public FileCollection getSourceFiles() {
            return new CompositeFileCollection() {
                @Override
                public String getDisplayName() {
                    return "task '" + task.getName() + "' source files";
                }

                @Override
                public void visitContents(FileCollectionResolveContext context) {
                    for (TaskInputFilePropertySpec filePropertySpec : getFileProperties()) {
                        if (filePropertySpec.isSkipWhenEmpty()) {
                            context.add(filePropertySpec.getPropertyFiles());
                        }
                    }
                }
            };
        }

        @Override
        public boolean hasSourceFiles() {
            return hasSourceFiles;
        }
    }

    private static class HasSourceFilesVisitor extends PropertyVisitor.Adapter {
        private boolean hasSourceFiles;

        @Override
        public void visitInputFileProperty(DeclaredTaskInputFileProperty inputFileProperty) {
            if (inputFileProperty.isSkipWhenEmpty()) {
                hasSourceFiles = true;
            }
        }

        public boolean hasSourceFiles() {
            return hasSourceFiles;
        }
    }

    private class GetInputPropertiesVisitor extends PropertyVisitor.Adapter implements TaskInputsInternal.GetInputPropertiesVisitor {
        private Map<String, Object> actualProperties;
        private List<DeclaredTaskInputProperty> declaredTaskInputProperties = new ArrayList<DeclaredTaskInputProperty>();

        @Override
        public void visitInputProperty(DeclaredTaskInputProperty inputProperty) {
            declaredTaskInputProperties.add(inputProperty);
        }

        @Override
        public Map<String, Object> getProperties() {
            if (actualProperties == null) {
                Map<String, Object> result = new HashMap<String, Object>();
                for (DeclaredTaskInputProperty declaredTaskInputProperty : declaredTaskInputProperties) {
                    String propertyName = declaredTaskInputProperty.getPropertyName();
                    try {
                        Object value = prepareValue(declaredTaskInputProperty.getValue());
                        result.put(propertyName, value);
                    } catch (Exception ex) {
                        throw new InvalidUserDataException(String.format("Error while evaluating property '%s' of %s", propertyName, task), ex);
                    }
                }
                actualProperties = result;
            }
            return actualProperties;
        }
    }
}
