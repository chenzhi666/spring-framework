/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.test.util;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotationCollectors;
import org.springframework.core.annotation.MergedAnnotationPredicates;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.annotation.RepeatableContainers;
import org.springframework.core.style.ToStringCreator;
import org.springframework.lang.Nullable;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentLruCache;
import org.springframework.util.ObjectUtils;

/**
 * {@code MetaAnnotationUtils} is a collection of utility methods that complements
 * the standard support already available in {@link AnnotationUtils}.
 *
 * <p>Mainly for internal use within the framework.
 *
 * <p>Whereas {@code AnnotationUtils} provides utilities for <em>getting</em> or
 * <em>finding</em> an annotation, {@code MetaAnnotationUtils} goes a step further
 * by providing support for determining the <em>root class</em> on which an
 * annotation is declared, either directly or indirectly via a <em>composed
 * annotation</em>. This additional information is encapsulated in an
 * {@link AnnotationDescriptor}.
 *
 * <p>The additional information provided by an {@code AnnotationDescriptor} is
 * required by the <em>Spring TestContext Framework</em> in order to be able to
 * support class hierarchy traversals for annotations such as
 * {@link org.springframework.test.context.ContextConfiguration @ContextConfiguration},
 * {@link org.springframework.test.context.TestExecutionListeners @TestExecutionListeners},
 * and {@link org.springframework.test.context.ActiveProfiles @ActiveProfiles}
 * which offer support for merging and overriding various <em>inherited</em>
 * annotation attributes (e.g.
 * {@link org.springframework.test.context.ContextConfiguration#inheritLocations}).
 *
 * @author Sam Brannen
 * @since 4.0
 * @see AnnotationUtils
 * @see AnnotationDescriptor
 */
public abstract class MetaAnnotationUtils {

	private static final ConcurrentLruCache<Class<?>, SearchStrategy> cachedSearchStrategies =
			new ConcurrentLruCache<>(32, MetaAnnotationUtils::lookUpSearchStrategy);


	/**
	 * Find the first annotation of the specified {@code annotationType} within
	 * the annotation hierarchy <em>above</em> the supplied class, merge that
	 * annotation's attributes with <em>matching</em> attributes from annotations
	 * in lower levels of the annotation hierarchy, and synthesize the result back
	 * into an annotation of the specified {@code annotationType}.
	 * <p>In the context of this method, the term "above" means within the
	 * {@linkplain Class#getSuperclass() superclass} hierarchy or within the
	 * {@linkplain Class#getEnclosingClass() enclosing class} hierarchy of the
	 * supplied class. The enclosing class hierarchy will only be searched if
	 * appropriate.
	 * @param clazz the class to look for annotations on
	 * @param annotationType the type of annotation to look for
	 * @return the merged, synthesized {@code Annotation}, or {@code null} if not found
	 * @since 5.3
	 * @see AnnotatedElementUtils#findMergedAnnotation(java.lang.reflect.AnnotatedElement, Class)
	 * @see #findAnnotationDescriptor(Class, Class)
	 */
	@Nullable
	public static <T extends Annotation> T findMergedAnnotation(Class<?> clazz, Class<T> annotationType) {
		AnnotationDescriptor<T> descriptor = findAnnotationDescriptor(clazz, annotationType);
		return (descriptor != null ? descriptor.synthesizeAnnotation() : null);
	}

	/**
	 * Find the {@link AnnotationDescriptor} for the supplied {@code annotationType}
	 * on the supplied {@link Class}, traversing its annotations, interfaces, and
	 * superclasses if no annotation can be found on the given class itself.
	 * <p>This method explicitly handles class-level annotations which are not
	 * declared as {@linkplain java.lang.annotation.Inherited inherited} <em>as
	 * well as meta-annotations</em>.
	 * <p>The algorithm operates as follows:
	 * <ol>
	 * <li>Search for the annotation on the given class and return a corresponding
	 * {@code AnnotationDescriptor} if found.
	 * <li>Recursively search through all annotations that the given class declares.
	 * <li>Recursively search through all interfaces implemented by the given class.
	 * <li>Recursively search through the superclass hierarchy of the given class.
	 * </ol>
	 * <p>In this context, the term <em>recursively</em> means that the search
	 * process continues by returning to step #1 with the current annotation,
	 * interface, or superclass as the class to look for annotations on.
	 * @param clazz the class to look for annotations on
	 * @param annotationType the type of annotation to look for
	 * @return the corresponding annotation descriptor if the annotation was found;
	 * otherwise {@code null}
	 * @see #findAnnotationDescriptorForTypes(Class, Class...)
	 */
	@Nullable
	public static <T extends Annotation> AnnotationDescriptor<T> findAnnotationDescriptor(
			Class<?> clazz, Class<T> annotationType) {

		return findAnnotationDescriptor(clazz, new HashSet<>(), annotationType);
	}

	/**
	 * Perform the search algorithm for {@link #findAnnotationDescriptor(Class, Class)},
	 * avoiding endless recursion by tracking which annotations have already been
	 * <em>visited</em>.
	 * @param clazz the class to look for annotations on
	 * @param visited the set of annotations that have already been visited
	 * @param annotationType the type of annotation to look for
	 * @return the corresponding annotation descriptor if the annotation was found;
	 * otherwise {@code null}
	 */
	@Nullable
	private static <T extends Annotation> AnnotationDescriptor<T> findAnnotationDescriptor(
			@Nullable Class<?> clazz, Set<Annotation> visited, Class<T> annotationType) {

		Assert.notNull(annotationType, "Annotation type must not be null");
		if (clazz == null || Object.class == clazz) {
			return null;
		}

		// Declared locally?
		if (AnnotationUtils.isAnnotationDeclaredLocally(annotationType, clazz)) {
			return new AnnotationDescriptor<>(clazz, clazz.getAnnotation(annotationType));
		}

		// Declared on a composed annotation (i.e., as a meta-annotation)?
		for (Annotation composedAnn : clazz.getDeclaredAnnotations()) {
			Class<? extends Annotation> composedType = composedAnn.annotationType();
			if (!AnnotationUtils.isInJavaLangAnnotationPackage(composedType.getName()) && visited.add(composedAnn)) {
				AnnotationDescriptor<T> descriptor = findAnnotationDescriptor(composedType, visited, annotationType);
				if (descriptor != null) {
					return new AnnotationDescriptor<>(
							clazz, descriptor.getDeclaringClass(), composedAnn, descriptor.getAnnotation());
				}
			}
		}

		// Declared on an interface?
		for (Class<?> ifc : clazz.getInterfaces()) {
			AnnotationDescriptor<T> descriptor = findAnnotationDescriptor(ifc, visited, annotationType);
			if (descriptor != null) {
				return new AnnotationDescriptor<>(clazz, descriptor.getDeclaringClass(),
						descriptor.getComposedAnnotation(), descriptor.getAnnotation());
			}
		}

		// Declared on a superclass?
		AnnotationDescriptor<T> descriptor =
				findAnnotationDescriptor(clazz.getSuperclass(), visited, annotationType);
		if (descriptor != null) {
			return descriptor;
		}

		// Declared on an enclosing class of an inner class?
		if (searchEnclosingClass(clazz)) {
			descriptor = findAnnotationDescriptor(clazz.getEnclosingClass(), visited, annotationType);
			if (descriptor != null) {
				return descriptor;
			}
		}

		return null;
	}

	/**
	 * Find the {@link UntypedAnnotationDescriptor} for the first {@link Class}
	 * in the inheritance hierarchy of the specified {@code clazz} (including
	 * the specified {@code clazz} itself) which declares at least one of the
	 * specified {@code annotationTypes}.
	 * <p>This method traverses the annotations, interfaces, and superclasses
	 * of the specified {@code clazz} if no annotation can be found on the given
	 * class itself.
	 * <p>This method explicitly handles class-level annotations which are not
	 * declared as {@linkplain java.lang.annotation.Inherited inherited} <em>as
	 * well as meta-annotations</em>.
	 * <p>The algorithm operates as follows:
	 * <ol>
	 * <li>Search for a local declaration of one of the annotation types on
	 * the given class and return a corresponding {@code UntypedAnnotationDescriptor}
	 * if found.
	 * <li>Recursively search through all annotations that the given class declares.
	 * <li>Recursively search through all interfaces implemented by the given class.
	 * <li>Recursively search through the superclass hierarchy of the given class.
	 * </ol>
	 * <p>In this context, the term <em>recursively</em> means that the search
	 * process continues by returning to step #1 with the current annotation,
	 * interface, or superclass as the class to look for annotations on.
	 * @param clazz the class to look for annotations on
	 * @param annotationTypes the types of annotations to look for
	 * @return the corresponding annotation descriptor if one of the annotations
	 * was found; otherwise {@code null}
	 * @see #findAnnotationDescriptor(Class, Class)
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public static UntypedAnnotationDescriptor findAnnotationDescriptorForTypes(
			Class<?> clazz, Class<? extends Annotation>... annotationTypes) {

		return findAnnotationDescriptorForTypes(clazz, new HashSet<>(), annotationTypes);
	}

	/**
	 * Perform the search algorithm for {@link #findAnnotationDescriptorForTypes(Class, Class...)},
	 * avoiding endless recursion by tracking which annotations have already been
	 * <em>visited</em>.
	 * @param clazz the class to look for annotations on
	 * @param visited the set of annotations that have already been visited
	 * @param annotationTypes the types of annotations to look for
	 * @return the corresponding annotation descriptor if one of the annotations
	 * was found; otherwise {@code null}
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	private static UntypedAnnotationDescriptor findAnnotationDescriptorForTypes(@Nullable Class<?> clazz,
			Set<Annotation> visited, Class<? extends Annotation>... annotationTypes) {

		assertNonEmptyAnnotationTypeArray(annotationTypes, "The list of annotation types must not be empty");
		if (clazz == null || Object.class == clazz) {
			return null;
		}

		// Declared locally?
		for (Class<? extends Annotation> annotationType : annotationTypes) {
			if (AnnotationUtils.isAnnotationDeclaredLocally(annotationType, clazz)) {
				return new UntypedAnnotationDescriptor(clazz, clazz.getAnnotation(annotationType), annotationTypes);
			}
		}

		// Declared on a composed annotation (i.e., as a meta-annotation)?
		for (Annotation composedAnnotation : clazz.getDeclaredAnnotations()) {
			if (!AnnotationUtils.isInJavaLangAnnotationPackage(composedAnnotation) && visited.add(composedAnnotation)) {
				UntypedAnnotationDescriptor descriptor = findAnnotationDescriptorForTypes(
						composedAnnotation.annotationType(), visited, annotationTypes);
				if (descriptor != null) {
					return new UntypedAnnotationDescriptor(clazz, descriptor.getDeclaringClass(),
							composedAnnotation, descriptor.getAnnotation(), annotationTypes);
				}
			}
		}

		// Declared on an interface?
		for (Class<?> ifc : clazz.getInterfaces()) {
			UntypedAnnotationDescriptor descriptor = findAnnotationDescriptorForTypes(ifc, visited, annotationTypes);
			if (descriptor != null) {
				return new UntypedAnnotationDescriptor(clazz, descriptor.getDeclaringClass(),
						descriptor.getComposedAnnotation(), descriptor.getAnnotation(), annotationTypes);
			}
		}

		// Declared on a superclass?
		UntypedAnnotationDescriptor descriptor =
				findAnnotationDescriptorForTypes(clazz.getSuperclass(), visited, annotationTypes);
		if (descriptor != null) {
			return descriptor;
		}

		// Declared on an enclosing class of an inner class?
		if (searchEnclosingClass(clazz)) {
			descriptor = findAnnotationDescriptorForTypes(clazz.getEnclosingClass(), visited, annotationTypes);
			if (descriptor != null) {
				return descriptor;
			}
		}

		return null;
	}

	@Nullable
	public static <T extends Annotation> RepeatableAnnotationDescriptor<T> findRepeatableAnnotationDescriptor(
			Class<?> clazz, Class<T> annotationType) {

		return findRepeatableAnnotationDescriptor(clazz, annotationType, new HashSet<>());
	}

	private static <T extends Annotation> RepeatableAnnotationDescriptor<T> findRepeatableAnnotationDescriptor(Class<?> clazz,
			Class<T> annotationType, Set<T> visited) {

		Assert.notNull(annotationType, "Annotation type must not be null");
		if (clazz == null || Object.class == clazz) {
			return null;
		}

		// Declared locally (directly present or meta-present)?
		T[] annotations = findRepeatableAnnotations(clazz, annotationType);
		// System.err.println(Arrays.toString(annotations));
		if (annotations.length > 0) {
			return new RepeatableAnnotationDescriptor<>(annotationType, clazz, clazz, annotations);
		}

		RepeatableAnnotationDescriptor<T> descriptor = null;

		// Declared on an interface?
		for (Class<?> ifc : clazz.getInterfaces()) {
			descriptor = findRepeatableAnnotationDescriptor(ifc, annotationType, visited);
			if (descriptor != null) {
				return new RepeatableAnnotationDescriptor<>(annotationType, clazz, descriptor.getDeclaringClass(),
					descriptor.getAnnotations());
			}
		}

		// Declared on a superclass?
		descriptor = findRepeatableAnnotationDescriptor(clazz.getSuperclass(), annotationType, visited);
		if (descriptor != null) {
			return descriptor;
		}

		// Declared on an enclosing class of an inner class?
		if (searchEnclosingClass(clazz)) {
			descriptor = findRepeatableAnnotationDescriptor(clazz.getEnclosingClass(), annotationType, visited);
			if (descriptor != null) {
				return descriptor;
			}
		}

		return null;
	}

	@SuppressWarnings("unchecked")
	private static <T extends Annotation> T[] findRepeatableAnnotations(Class<?> clazz, Class<T> annotationType) {
		RepeatableContainers repeatableContainers = RepeatableContainers.of(annotationType, null);
		return MergedAnnotations.from(clazz, SearchStrategy.DIRECT, repeatableContainers)
				.stream(annotationType)
				.collect(MergedAnnotationCollectors.toAnnotationSet())
				.toArray((T[]) Array.newInstance(annotationType, 0));
	}

	/**
	 * Get the {@link SearchStrategy} for the supplied class.
	 * @param clazz the class for which the search strategy should be resolved
	 * @return the resolved search strategy
	 * @since 5.3
	 */
	public static SearchStrategy getSearchStrategy(Class<?> clazz) {
		return cachedSearchStrategies.get(clazz);
	}

	private static SearchStrategy lookUpSearchStrategy(Class<?> clazz) {
		EnclosingConfiguration enclosingConfiguration =
			MergedAnnotations.from(clazz, SearchStrategy.TYPE_HIERARCHY_AND_ENCLOSING_CLASSES)
				.stream(NestedTestConfiguration.class)
				.map(mergedAnnotation -> mergedAnnotation.getEnum("value", EnclosingConfiguration.class))
				.findFirst()
				.orElse(EnclosingConfiguration.OVERRIDE);
		// TODO Consider making the default EnclosingConfiguration mode globally configurable via SpringProperties.
		return (enclosingConfiguration == EnclosingConfiguration.INHERIT ?
				SearchStrategy.TYPE_HIERARCHY_AND_ENCLOSING_CLASSES :
				SearchStrategy.TYPE_HIERARCHY);
	}

	/**
	 * Determine if annotations on the enclosing class of the supplied class
	 * should be searched by algorithms in {@link MetaAnnotationUtils}.
	 * @param clazz the class whose enclosing class should potentially be searched
	 * @return {@code true} if the supplied class is an inner class whose enclosing
	 * class should be searched
	 * @since 5.3
	 * @see ClassUtils#isInnerClass(Class)
	 * @see #getSearchStrategy(Class)
	 */
	public static boolean searchEnclosingClass(Class<?> clazz) {
		return (ClassUtils.isInnerClass(clazz) &&
				getSearchStrategy(clazz) == SearchStrategy.TYPE_HIERARCHY_AND_ENCLOSING_CLASSES);
	}

	private static void assertNonEmptyAnnotationTypeArray(Class<?>[] annotationTypes, String message) {
		if (ObjectUtils.isEmpty(annotationTypes)) {
			throw new IllegalArgumentException(message);
		}
		for (Class<?> clazz : annotationTypes) {
			if (!Annotation.class.isAssignableFrom(clazz)) {
				throw new IllegalArgumentException("Array elements must be of type Annotation");
			}
		}
	}


	/**
	 * Descriptor for an {@link Annotation}, including the {@linkplain
	 * #getDeclaringClass() class} on which the annotation is <em>declared</em>
	 * as well as the actual {@linkplain #getAnnotation() annotation} instance.
	 * <p>If the annotation is used as a meta-annotation, the descriptor also includes
	 * the {@linkplain #getComposedAnnotation() composed annotation} on which the
	 * annotation is present. In such cases, the <em>root declaring class</em> is
	 * not directly annotated with the annotation but rather indirectly via the
	 * composed annotation.
	 * <p>Given the following example, if we are searching for the {@code @Transactional}
	 * annotation <em>on</em> the {@code TransactionalTests} class, then the
	 * properties of the {@code AnnotationDescriptor} would be as follows.
	 * <ul>
	 * <li>rootDeclaringClass: {@code TransactionalTests} class object</li>
	 * <li>declaringClass: {@code TransactionalTests} class object</li>
	 * <li>composedAnnotation: {@code null}</li>
	 * <li>annotation: instance of the {@code Transactional} annotation</li>
	 * </ul>
	 * <p><pre style="code">
	 * &#064;Transactional
	 * &#064;ContextConfiguration({"/test-datasource.xml", "/repository-config.xml"})
	 * public class TransactionalTests { }
	 * </pre>
	 * <p>Given the following example, if we are searching for the {@code @Transactional}
	 * annotation <em>on</em> the {@code UserRepositoryTests} class, then the
	 * properties of the {@code AnnotationDescriptor} would be as follows.
	 * <ul>
	 * <li>rootDeclaringClass: {@code UserRepositoryTests} class object</li>
	 * <li>declaringClass: {@code RepositoryTests} class object</li>
	 * <li>composedAnnotation: instance of the {@code RepositoryTests} annotation</li>
	 * <li>annotation: instance of the {@code Transactional} annotation</li>
	 * </ul>
	 * <p><pre style="code">
	 * &#064;Transactional
	 * &#064;ContextConfiguration({"/test-datasource.xml", "/repository-config.xml"})
	 * &#064;Retention(RetentionPolicy.RUNTIME)
	 * public &#064;interface RepositoryTests { }
	 *
	 * &#064;RepositoryTests
	 * public class UserRepositoryTests { }
	 * </pre>
	 *
	 * @param <T> the annotation type
	 */
	public static class AnnotationDescriptor<T extends Annotation> {

		private final Class<?> rootDeclaringClass;

		private final Class<?> declaringClass;

		@Nullable
		private final Annotation composedAnnotation;

		private final T annotation;

		private final AnnotationAttributes annotationAttributes;

		public AnnotationDescriptor(Class<?> rootDeclaringClass, T annotation) {
			this(rootDeclaringClass, rootDeclaringClass, null, annotation);
		}

		public AnnotationDescriptor(Class<?> rootDeclaringClass, Class<?> declaringClass,
				@Nullable Annotation composedAnnotation, T annotation) {

			Assert.notNull(rootDeclaringClass, "'rootDeclaringClass' must not be null");
			Assert.notNull(annotation, "Annotation must not be null");
			this.rootDeclaringClass = rootDeclaringClass;
			this.declaringClass = declaringClass;
			this.composedAnnotation = composedAnnotation;
			this.annotation = annotation;
			AnnotationAttributes attributes = AnnotatedElementUtils.findMergedAnnotationAttributes(
					rootDeclaringClass, annotation.annotationType().getName(), false, false);
			Assert.state(attributes != null, "No annotation attributes");
			this.annotationAttributes = attributes;
		}

		public Class<?> getRootDeclaringClass() {
			return this.rootDeclaringClass;
		}

		public Class<?> getDeclaringClass() {
			return this.declaringClass;
		}

		public T getAnnotation() {
			return this.annotation;
		}

		/**
		 * Synthesize the merged {@link #getAnnotationAttributes AnnotationAttributes}
		 * in this descriptor back into an annotation of the target
		 * {@linkplain #getAnnotationType annotation type}.
		 * @since 4.2
		 * @see #getAnnotationAttributes()
		 * @see #getAnnotationType()
		 * @see AnnotationUtils#synthesizeAnnotation(java.util.Map, Class, java.lang.reflect.AnnotatedElement)
		 */
		@SuppressWarnings("unchecked")
		public T synthesizeAnnotation() {
			return AnnotationUtils.synthesizeAnnotation(
					getAnnotationAttributes(), (Class<T>) getAnnotationType(), getRootDeclaringClass());
		}

		public Class<? extends Annotation> getAnnotationType() {
			return this.annotation.annotationType();
		}

		public AnnotationAttributes getAnnotationAttributes() {
			return this.annotationAttributes;
		}

		@Nullable
		public Annotation getComposedAnnotation() {
			return this.composedAnnotation;
		}

		@Nullable
		public Class<? extends Annotation> getComposedAnnotationType() {
			return (this.composedAnnotation != null ? this.composedAnnotation.annotationType() : null);
		}

		/**
		 * Find the next {@link AnnotationDescriptor} for the specified
		 * {@linkplain #getAnnotationType() annotation type} in the hierarchy
		 * above the {@linkplain #getRootDeclaringClass() root declaring class}
		 * of this descriptor.
		 * <p>If a corresponding annotation is found in the superclass hierarchy
		 * of the root declaring class, that will be returned. Otherwise, an
		 * attempt will be made to find a corresponding annotation in the
		 * {@linkplain Class#getEnclosingClass() enclosing class} hierarchy of
		 * the root declaring class if
		 * {@linkplain MetaAnnotationUtils#searchEnclosingClass appropriate}.
		 * @return the next corresponding annotation descriptor if the annotation
		 * was found; otherwise {@code null}
		 * @since 5.3
		 */
		@Nullable
		@SuppressWarnings("unchecked")
		public AnnotationDescriptor<T> next() {
			Class<T> annotationType = (Class<T>) getAnnotationType();
			// Declared on a superclass?
			AnnotationDescriptor<T> descriptor =
					findAnnotationDescriptor(getRootDeclaringClass().getSuperclass(), annotationType);
			// Declared on an enclosing class of an inner class?
			if (descriptor == null && searchEnclosingClass(getRootDeclaringClass())) {
				descriptor = findAnnotationDescriptor(getRootDeclaringClass().getEnclosingClass(), annotationType);
			}
			return descriptor;
		}

		/**
		 * Find <strong>all</strong> annotations of the specified
		 * {@linkplain #getAnnotationType() annotation type} that are present or
		 * meta-present on the {@linkplain #getRootDeclaringClass() root declaring
		 * class} of this descriptor.
		 * @return the set of all merged, synthesized {@code Annotations} found,
		 * or an empty set if none were found
		 * @since 5.3
		 */
		@SuppressWarnings("unchecked")
		public Set<T> findAllLocalMergedAnnotations() {
			Class<T> annotationType = (Class<T>) getAnnotationType();
			SearchStrategy searchStrategy = getSearchStrategy(getRootDeclaringClass());
			return MergedAnnotations.from(getRootDeclaringClass(), searchStrategy , RepeatableContainers.none())
					.stream(annotationType)
					.filter(MergedAnnotationPredicates.firstRunOf(MergedAnnotation::getAggregateIndex))
					.collect(MergedAnnotationCollectors.toAnnotationSet());
		}

		/**
		 * Provide a textual representation of this {@code AnnotationDescriptor}.
		 */
		@Override
		public String toString() {
			return new ToStringCreator(this)
					.append("rootDeclaringClass", this.rootDeclaringClass)
					.append("declaringClass", this.declaringClass)
					.append("composedAnnotation", this.composedAnnotation)
					.append("annotation", this.annotation)
					.toString();
		}
	}


	/**
	 * <em>Untyped</em> extension of {@link AnnotationDescriptor} that is used
	 * to describe the declaration of one of several candidate annotation types
	 * where the actual annotation type cannot be predetermined.
	 */
	public static class UntypedAnnotationDescriptor extends AnnotationDescriptor<Annotation> {

		@Nullable
		private final Class<? extends Annotation>[] annotationTypes;

		/**
		 * Create a new {@plain UntypedAnnotationDescriptor}.
		 * @deprecated As of Spring Framework 5.3, in favor of
		 * {@link UntypedAnnotationDescriptor#UntypedAnnotationDescriptor(Class, Annotation, Class[])}
		 */
		@Deprecated
		public UntypedAnnotationDescriptor(Class<?> rootDeclaringClass, Annotation annotation) {
			this(rootDeclaringClass, annotation, null);
		}

		public UntypedAnnotationDescriptor(Class<?> rootDeclaringClass, Annotation annotation,
				@Nullable Class<? extends Annotation>[] annotationTypes) {

			this(rootDeclaringClass, rootDeclaringClass, null, annotation, annotationTypes);
		}

		/**
		 * Create a new {@plain UntypedAnnotationDescriptor}.
		 * @deprecated As of Spring Framework 5.3, in favor of
		 * {@link UntypedAnnotationDescriptor#UntypedAnnotationDescriptor(Class, Class, Annotation, Annotation, Class[])}
		 */
		@Deprecated
		public UntypedAnnotationDescriptor(Class<?> rootDeclaringClass, Class<?> declaringClass,
				@Nullable Annotation composedAnnotation, Annotation annotation) {

			this(rootDeclaringClass, declaringClass, composedAnnotation, annotation, null);
		}

		public UntypedAnnotationDescriptor(Class<?> rootDeclaringClass, Class<?> declaringClass,
				@Nullable Annotation composedAnnotation, Annotation annotation,
				@Nullable Class<? extends Annotation>[] annotationTypes) {

			super(rootDeclaringClass, declaringClass, composedAnnotation, annotation);
			this.annotationTypes = annotationTypes;
		}

		/**
		 * Throws an {@link UnsupportedOperationException} since the type of annotation
		 * represented by an {@code UntypedAnnotationDescriptor} is unknown.
		 * @since 4.2
		 */
		@Override
		public Annotation synthesizeAnnotation() {
			throw new UnsupportedOperationException(
					"synthesizeAnnotation() is unsupported in UntypedAnnotationDescriptor");
		}

		/**
		 * Find the next {@link UntypedAnnotationDescriptor} for the specified
		 * annotation types in the hierarchy above the
		 * {@linkplain #getRootDeclaringClass() root declaring class} of this
		 * descriptor.
		 * <p>If one of the corresponding annotations is found in the superclass
		 * hierarchy of the root declaring class, that will be returned. Otherwise,
		 * an attempt will be made to find a corresponding annotation in the
		 * {@linkplain Class#getEnclosingClass() enclosing class} hierarchy of
		 * the root declaring class if
		 * {@linkplain MetaAnnotationUtils#searchEnclosingClass appropriate}.
		 * @return the next corresponding annotation descriptor if one of the
		 * annotations was found; otherwise {@code null}
		 * @since 5.3
		 * @see AnnotationDescriptor#next()
		 */
		@Override
		@Nullable
		public UntypedAnnotationDescriptor next() {
			if (ObjectUtils.isEmpty(this.annotationTypes)) {
				throw new UnsupportedOperationException(
						"next() is unsupported if UntypedAnnotationDescriptor is instantiated without 'annotationTypes'");
			}

			// Declared on a superclass?
			UntypedAnnotationDescriptor descriptor =
					findAnnotationDescriptorForTypes(getRootDeclaringClass().getSuperclass(), this.annotationTypes);
			// Declared on an enclosing class of an inner class?
			if (descriptor == null && searchEnclosingClass(getRootDeclaringClass())) {
				descriptor = findAnnotationDescriptorForTypes(getRootDeclaringClass().getEnclosingClass(), this.annotationTypes);
			}
			return descriptor;
		}

		/**
		 * Throws an {@link UnsupportedOperationException} since the type of annotation
		 * represented by an {@code UntypedAnnotationDescriptor} is unknown.
		 * @since 5.3
		 */
		@Override
		public Set<Annotation> findAllLocalMergedAnnotations() {
			throw new UnsupportedOperationException(
					"findAllLocalMergedAnnotations() is unsupported in UntypedAnnotationDescriptor");
		}

	}


	public static class RepeatableAnnotationDescriptor<T extends Annotation> {

		private final Class<T> annotationType;

		private final Class<?> rootDeclaringClass;

		private final Class<?> declaringClass;

		private final T[] annotations;


		public RepeatableAnnotationDescriptor(Class<T> annotationType, Class<?> rootDeclaringClass,
				Class<?> declaringClass, T[] annotations) {

			Assert.notNull(annotationType, "'annotationType' must not be null");
			Assert.notNull(rootDeclaringClass, "'rootDeclaringClass' must not be null");
			Assert.notNull(declaringClass, "'declaringClass' must not be null");
			Assert.notNull(annotations, "'annotations' array must not be null");
			Assert.notEmpty(annotations, "'annotations' array must not be empty");

			this.annotationType = annotationType;
			this.rootDeclaringClass = rootDeclaringClass;
			this.declaringClass = declaringClass;
			this.annotations = annotations;
		}

		public Class<? extends Annotation> getAnnotationType() {
			return this.annotationType;
		}

		public Class<?> getRootDeclaringClass() {
			return this.rootDeclaringClass;
		}

		public Class<?> getDeclaringClass() {
			return this.declaringClass;
		}

		public T[] getAnnotations() {
			return this.annotations;
		}

		@Nullable
		public RepeatableAnnotationDescriptor<T> next() {
			// Declared on a superclass?
			RepeatableAnnotationDescriptor<T> descriptor =
					findRepeatableAnnotationDescriptor(getRootDeclaringClass().getSuperclass(), this.annotationType);
			// Declared on an enclosing class of an inner class?
			if (descriptor == null && searchEnclosingClass(getRootDeclaringClass())) {
				descriptor = findRepeatableAnnotationDescriptor(getRootDeclaringClass().getEnclosingClass(), this.annotationType);
			}
			return descriptor;
		}

		@Override
		public String toString() {
			return new ToStringCreator(this)
					.append("annotationType", this.annotationType)
					.append("rootDeclaringClass", this.rootDeclaringClass)
					.append("declaringClass", this.declaringClass)
					.append("annotation", Arrays.toString(this.annotations))
					.toString();
		}
	}

}
