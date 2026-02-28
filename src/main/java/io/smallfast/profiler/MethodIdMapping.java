package io.smallfast.profiler;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.implementation.bytecode.constant.IntegerConstant;

import java.util.concurrent.atomic.AtomicInteger;

public final class MethodIdMapping implements Advice.OffsetMapping {

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private MethodIdMapping() {}

    @Override
    public Target resolve(TypeDescription instrumentedType,
                          MethodDescription instrumentedMethod,
                          Assigner assigner,
                          Advice.ArgumentHandler argumentHandler,
                          Advice.OffsetMapping.Sort sort) {

        // Assign unique ID once per instrumented method
        int id = NEXT_ID.getAndIncrement();

        // Register readable method name ONCE (not per call)
        String name = instrumentedType.getName()
                + "." + instrumentedMethod.getName()
                + instrumentedMethod.getDescriptor();

        MethodRegistry.register(id, name);

        StackManipulation sm = IntegerConstant.forValue(id);
        return Target.ForStackManipulation.of(id);
    }

    // Factory for @MethodId binding
    public static final class Factory implements Advice.OffsetMapping.Factory<MethodId> {

        @Override
        public Class<MethodId> getAnnotationType() {
            return MethodId.class;
        }

        @Override
        public Advice.OffsetMapping make(ParameterDescription.InDefinedShape target,
                                         AnnotationDescription.Loadable<MethodId> annotation,
                                         AdviceType adviceType) {

            return new MethodIdMapping();
        }
    }
}