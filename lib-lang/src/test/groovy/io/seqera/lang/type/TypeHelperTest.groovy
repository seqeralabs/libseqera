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

}
