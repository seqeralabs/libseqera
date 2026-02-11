/*
 * Copyright 2026, Seqera Labs
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

package io.seqera.serde.gson

import com.google.gson.JsonParseException
import spock.lang.Specification

/**
 * Tests for {@link RuntimeTypeAdapterFactory}
 *
 * @author Paolo Di Tommaso
 */
class RuntimeTypeAdapterFactoryTest extends Specification {

    // === Test Hierarchy ===

    static interface Animal {
        String getName()
    }

    static class Dog implements Animal {
        String name
        int barkVolume

        @Override
        String getName() { name }
    }

    static class Cat implements Animal {
        String name
        boolean lazy

        @Override
        String getName() { name }
    }

    static class Bird implements Animal {
        String name
        String species
        boolean canFly

        @Override
        String getName() { name }
    }

    // === Basic Polymorphic Serialization Tests ===

    def 'should serialize with type discriminator field'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog, 'Dog')
            .registerSubtype(Cat, 'Cat')
        def encoder = new GsonEncodingStrategy<Animal>() {}
            .withTypeAdapterFactory(factory)
        def dog = new Dog(name: 'Rex', barkVolume: 10)

        when:
        def json = encoder.encode(dog)

        then:
        json.contains('"@type":"Dog"')
        json.contains('"name":"Rex"')
        json.contains('"barkVolume":10')
    }

    def 'should deserialize to correct subtype - Dog'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog, 'Dog')
            .registerSubtype(Cat, 'Cat')
        def encoder = new GsonEncodingStrategy<Animal>() {}
            .withTypeAdapterFactory(factory)
        def json = '{"@type":"Dog","name":"Rex","barkVolume":10}'

        when:
        def animal = encoder.decode(json)

        then:
        animal instanceof Dog
        ((Dog) animal).name == 'Rex'
        ((Dog) animal).barkVolume == 10
    }

    def 'should deserialize to correct subtype - Cat'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog, 'Dog')
            .registerSubtype(Cat, 'Cat')
        def encoder = new GsonEncodingStrategy<Animal>() {}
            .withTypeAdapterFactory(factory)
        def json = '{"@type":"Cat","name":"Whiskers","lazy":true}'

        when:
        def animal = encoder.decode(json)

        then:
        animal instanceof Cat
        ((Cat) animal).name == 'Whiskers'
        ((Cat) animal).lazy == true
    }

    // === Custom Type Field Name Tests ===

    def 'should support custom type field name'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, 'kind')
            .registerSubtype(Dog, 'Dog')
            .registerSubtype(Cat, 'Cat')
        def encoder = new GsonEncodingStrategy<Animal>() {}
            .withTypeAdapterFactory(factory)
        def cat = new Cat(name: 'Mittens', lazy: false)

        when:
        def json = encoder.encode(cat)

        then:
        json.contains('"kind":"Cat"')
        !json.contains('"@type"')

        when:
        def decoded = encoder.decode(json)

        then:
        decoded instanceof Cat
        ((Cat) decoded).name == 'Mittens'
    }

    def 'should use default type field name when not specified'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal)
            .registerSubtype(Dog, 'Dog')
        def encoder = new GsonEncodingStrategy<Animal>() {}
            .withTypeAdapterFactory(factory)
        def dog = new Dog(name: 'Buddy', barkVolume: 5)

        when:
        def json = encoder.encode(dog)

        then:
        json.contains('"type":"Dog"')
    }

    // === Multiple Subtypes Tests ===

    def 'should handle multiple subtypes'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog, 'Dog')
            .registerSubtype(Cat, 'Cat')
            .registerSubtype(Bird, 'Bird')
        def encoder = new GsonEncodingStrategy<Animal>() {}
            .withTypeAdapterFactory(factory)

        when:
        def dogJson = encoder.encode(new Dog(name: 'Rex', barkVolume: 10))
        def catJson = encoder.encode(new Cat(name: 'Whiskers', lazy: true))
        def birdJson = encoder.encode(new Bird(name: 'Tweety', species: 'Canary', canFly: true))

        then:
        dogJson.contains('"@type":"Dog"')
        catJson.contains('"@type":"Cat"')
        birdJson.contains('"@type":"Bird"')
        birdJson.contains('"species":"Canary"')
        birdJson.contains('"canFly":true')

        when:
        def decodedBird = encoder.decode(birdJson)

        then:
        decodedBird instanceof Bird
        ((Bird) decodedBird).name == 'Tweety'
        ((Bird) decodedBird).species == 'Canary'
        ((Bird) decodedBird).canFly == true
    }

    // === Error Handling Tests ===

    def 'should throw on unknown type during deserialization'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog, 'Dog')
        def encoder = new GsonEncodingStrategy<Animal>() {}
            .withTypeAdapterFactory(factory)
        def json = '{"@type":"Unknown","name":"Test"}'

        when:
        encoder.decode(json)

        then:
        def e = thrown(RuntimeException)
        e.cause instanceof JsonParseException
        e.cause.message.contains('did you forget to register a subtype')
    }

    def 'should throw on missing type field during deserialization'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog, 'Dog')
        def encoder = new GsonEncodingStrategy<Animal>() {}
            .withTypeAdapterFactory(factory)
        def json = '{"name":"Rex","barkVolume":10}'

        when:
        encoder.decode(json)

        then:
        def e = thrown(RuntimeException)
        e.cause instanceof JsonParseException
        e.cause.message.contains('does not define a field named @type')
    }

    def 'should throw on unregistered subtype during serialization'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog, 'Dog')
            // Cat not registered
        def encoder = new GsonEncodingStrategy<Animal>() {}
            .withTypeAdapterFactory(factory)
        def cat = new Cat(name: 'Whiskers', lazy: true)

        when:
        encoder.encode(cat)

        then:
        def e = thrown(RuntimeException)
        e.cause instanceof JsonParseException
        e.cause.message.contains('did you forget to register a subtype')
    }

    // === Simple Name Registration Tests ===

    def 'should use simple class name as label when not specified'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog)  // Uses Dog.class.getSimpleName() = "Dog"
            .registerSubtype(Cat)
        def encoder = new GsonEncodingStrategy<Animal>() {}
            .withTypeAdapterFactory(factory)
        def dog = new Dog(name: 'Rex', barkVolume: 10)

        when:
        def json = encoder.encode(dog)

        then:
        json.contains('"@type":"Dog"')
    }

    // === Null Handling Tests ===

    def 'should handle null values'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog, 'Dog')
        def encoder = new GsonEncodingStrategy<Animal>() {}
            .withTypeAdapterFactory(factory)

        expect:
        encoder.encode(null) == null
        encoder.decode(null) == null
    }

    // === Round-trip Tests ===

    def 'should round-trip all subtypes correctly'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog, 'Dog')
            .registerSubtype(Cat, 'Cat')
            .registerSubtype(Bird, 'Bird')
        def encoder = new GsonEncodingStrategy<Animal>() {}
            .withTypeAdapterFactory(factory)

        and:
        def animals = [
            new Dog(name: 'Rex', barkVolume: 10),
            new Cat(name: 'Whiskers', lazy: true),
            new Bird(name: 'Tweety', species: 'Canary', canFly: true)
        ]

        expect:
        animals.each { animal ->
            def json = encoder.encode(animal)
            def decoded = encoder.decode(json)
            assert decoded.class == animal.class
            assert decoded.name == animal.name
        }
    }

    // === Registration Validation Tests ===

    def 'should throw on duplicate type registration'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog, 'Dog')

        when:
        factory.registerSubtype(Dog, 'AnotherDog')

        then:
        thrown(IllegalArgumentException)
    }

    def 'should throw on duplicate label registration'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog, 'Animal')

        when:
        factory.registerSubtype(Cat, 'Animal')

        then:
        thrown(IllegalArgumentException)
    }

    // === Protected Method Tests ===

    def 'should expose label and subtype lookups'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog, 'Canine')
            .registerSubtype(Cat, 'Feline')

        expect:
        factory.getSubTypeFromLabel('Canine') == Dog
        factory.getSubTypeFromLabel('Feline') == Cat
        factory.getSubTypeFromLabel('Unknown') == null

        and:
        factory.getLabelFromSubtype(Dog) == 'Canine'
        factory.getLabelFromSubtype(Cat) == 'Feline'
        factory.getLabelFromSubtype(Bird) == null

        and:
        factory.getTypeFieldName() == '@type'
    }
}
