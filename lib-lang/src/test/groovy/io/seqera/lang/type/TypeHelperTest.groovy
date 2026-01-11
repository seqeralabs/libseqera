/*
 * Copyright 2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.seqera.lang.type

import spock.lang.Specification

import java.lang.reflect.Type

/**
 * Test for TypeHelper class
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class TypeHelperTest extends Specification {

    static class GenericContainer<T> {
    }

    static class StringContainer extends GenericContainer<String> {
    }
    
    static class IntegerContainer extends GenericContainer<Integer> {
    }
    
    static class MultiGenericContainer<T, U> {
    }
    
    static class StringIntegerContainer extends MultiGenericContainer<String, Integer> {
    }

    def 'should get generic type at index 0'() {
        given:
        def stringContainer = new StringContainer()
        
        when:
        Type type = TypeHelper.getGenericType(stringContainer, 0)
        
        then:
        type == String.class
    }

    def 'should get generic type at index 0 for integer'() {
        given:
        def integerContainer = new IntegerContainer()
        
        when:
        Type type = TypeHelper.getGenericType(integerContainer, 0)
        
        then:
        type == Integer.class
    }

    def 'should get generic type at different indices'() {
        given:
        def multiContainer = new StringIntegerContainer()
        
        when:
        Type type0 = TypeHelper.getGenericType(multiContainer, 0)
        Type type1 = TypeHelper.getGenericType(multiContainer, 1)
        
        then:
        type0 == String.class
        type1 == Integer.class
    }

    def 'should throw exception for invalid index'() {
        given:
        def stringContainer = new StringContainer()

        when:
        TypeHelper.getGenericType(stringContainer, 1)

        then:
        thrown(ArrayIndexOutOfBoundsException)
    }

    // Test fixtures for interface type arguments

    interface Handler<P, R> {}

    interface SingleTypeHandler<T> {}

    static class StringIntegerHandler implements Handler<String, Integer> {}

    static class BooleanHandler implements SingleTypeHandler<Boolean> {}

    static abstract class AbstractHandler<P, R> implements Handler<P, R> {}

    static class ConcreteAbstractHandler extends AbstractHandler<Long, Double> implements Handler<Long, Double> {}

    static class MultiInterfaceHandler implements Handler<String, Integer>, SingleTypeHandler<Boolean> {}

    static class RawHandler implements Handler {}

    // Tests for getInterfaceTypeArguments

    def 'should get interface type arguments for directly implemented interface'() {
        when:
        def types = TypeHelper.getInterfaceTypeArguments(StringIntegerHandler, Handler)

        then:
        types.length == 2
        types[0] == String
        types[1] == Integer
    }

    def 'should get interface type arguments for single type parameter'() {
        when:
        def types = TypeHelper.getInterfaceTypeArguments(BooleanHandler, SingleTypeHandler)

        then:
        types.length == 1
        types[0] == Boolean
    }

    def 'should get interface type arguments when extending abstract class'() {
        when:
        def types = TypeHelper.getInterfaceTypeArguments(ConcreteAbstractHandler, Handler)

        then:
        types.length == 2
        types[0] == Long
        types[1] == Double
    }

    def 'should get correct interface when class implements multiple interfaces'() {
        when:
        def handlerTypes = TypeHelper.getInterfaceTypeArguments(MultiInterfaceHandler, Handler)
        def singleTypes = TypeHelper.getInterfaceTypeArguments(MultiInterfaceHandler, SingleTypeHandler)

        then:
        handlerTypes.length == 2
        handlerTypes[0] == String
        handlerTypes[1] == Integer

        and:
        singleTypes.length == 1
        singleTypes[0] == Boolean
    }

    def 'should return null for interface not implemented by class'() {
        when:
        def types = TypeHelper.getInterfaceTypeArguments(StringContainer, Handler)

        then:
        types == null
    }

    def 'should return null for raw type implementation'() {
        when:
        def types = TypeHelper.getInterfaceTypeArguments(RawHandler, Handler)

        then:
        types == null
    }

    // Tests for getRawType

    def 'should get raw type from Class'() {
        expect:
        TypeHelper.getRawType(String) == String
        TypeHelper.getRawType(Integer) == Integer
        TypeHelper.getRawType(List) == List
    }

    def 'should get raw type from ParameterizedType'() {
        given:
        def types = TypeHelper.getInterfaceTypeArguments(StringIntegerHandler, Handler)

        expect:
        TypeHelper.getRawType(types[0]) == String
        TypeHelper.getRawType(types[1]) == Integer
    }

    def 'should get raw type from nested ParameterizedType'() {
        given:
        // Create a class that uses List<String> as a type argument
        def types = TypeHelper.getInterfaceTypeArguments(ListHandler, SingleTypeHandler)

        when:
        def rawType = TypeHelper.getRawType(types[0])

        then:
        rawType == List
    }

    def 'should throw exception for unsupported type'() {
        given:
        // TypeVariable is not supported
        def typeVar = Handler.getTypeParameters()[0]

        when:
        TypeHelper.getRawType(typeVar)

        then:
        thrown(IllegalArgumentException)
    }

    static class ListHandler implements SingleTypeHandler<List<String>> {}

}
