package com.airbnb.epoxy;

import com.airbnb.epoxy.EpoxyAttribute.Option;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import static com.airbnb.epoxy.Utils.capitalizeFirstLetter;
import static com.airbnb.epoxy.Utils.isFieldPackagePrivate;
import static com.airbnb.epoxy.Utils.startsWithIs;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;

class BaseModelAttributeInfo extends AttributeInfo {

  private final TypeElement classElement;
  protected Types typeUtils;

  BaseModelAttributeInfo(Element attribute, Types typeUtils, Elements elements,
      ErrorLogger errorLogger) {
    this.typeUtils = typeUtils;
    this.fieldName = attribute.getSimpleName().toString();
    typeMirror = attribute.asType();
    setJavaDocString(elements.getDocComment(attribute));

    classElement = (TypeElement) attribute.getEnclosingElement();
    modelName = classElement.getSimpleName().toString();
    modelPackageName = elements.getPackageOf(classElement).getQualifiedName().toString();
    this.hasSuperSetter = hasSuperMethod(classElement, attribute);
    this.hasFinalModifier = attribute.getModifiers().contains(FINAL);
    this.packagePrivate = isFieldPackagePrivate(attribute);

    EpoxyAttribute annotation = attribute.getAnnotation(EpoxyAttribute.class);

    Set<Option> options = new HashSet<>(Arrays.asList(annotation.value()));
    validateAnnotationOptions(errorLogger, annotation, options);

    //noinspection deprecation
    useInHash = annotation.hash() && !options.contains(Option.DoNotHash);
    ignoreRequireHashCode = options.contains(Option.IgnoreRequireHashCode);
    doNotUseInToString = options.contains(Option.DoNotUseInToString);

    generateSetter = annotation.setter() && !options.contains(Option.NoSetter);
    generateGetter = !options.contains(Option.NoGetter);

    isPrivate = attribute.getModifiers().contains(PRIVATE);
    if (isPrivate) {
      findGetterAndSetterForPrivateField(errorLogger);
    }

    buildAnnotationLists(attribute.getAnnotationMirrors());
  }

  /**
   * Check if the given class or any of its super classes have a super method with the given name.
   * Private methods are ignored since the generated subclass can't call super on those.
   */
  protected boolean hasSuperMethod(TypeElement classElement, Element attribute) {
    if (!Utils.isEpoxyModel(classElement.asType())) {
      return false;
    }

    for (Element subElement : classElement.getEnclosedElements()) {
      if (subElement.getKind() == ElementKind.METHOD) {
        ExecutableElement method = (ExecutableElement) subElement;
        if (!method.getModifiers().contains(Modifier.PRIVATE)
            && method.getSimpleName().toString().equals(attribute.getSimpleName().toString())
            && method.getParameters().size() == 1
            && method.getParameters().get(0).asType().equals(attribute.asType())) {
          return true;
        }
      }
    }

    Element superClass = typeUtils.asElement(classElement.getSuperclass());
    return (superClass instanceof TypeElement)
        && hasSuperMethod((TypeElement) superClass, attribute);
  }

  private void validateAnnotationOptions(ErrorLogger errorLogger, EpoxyAttribute annotation,
      Set<Option> options) {

    if (options.contains(Option.IgnoreRequireHashCode) && options.contains(Option.DoNotHash)) {
      errorLogger
          .logError("Illegal to use both %s and %s options in an %s annotation. (%s#%s)",
              Option.DoNotHash,
              Option.IgnoreRequireHashCode,
              EpoxyAttribute.class.getSimpleName(),
              classElement.getSimpleName(),
              fieldName);
    }

    // Don't let legacy values be mixed with the new Options values
    if (!options.isEmpty()) {
      if (!annotation.hash()) {
        errorLogger
            .logError("Don't use hash=false in an %s if you are using options. Instead, use the"
                    + " %s option. (%s#%s)",
                EpoxyAttribute.class.getSimpleName(),
                Option.DoNotHash,
                classElement.getSimpleName(),
                fieldName);
      }

      if (!annotation.setter()) {
        errorLogger
            .logError("Don't use setter=false in an %s if you are using options. Instead, use the"
                    + " %s option. (%s#%s)",
                EpoxyAttribute.class.getSimpleName(),
                Option.NoSetter,
                classElement.getSimpleName(),
                fieldName);
      }
    }
  }

  /**
   * Checks if the given private field has getter and setter for access to it
   */
  private void findGetterAndSetterForPrivateField(ErrorLogger errorLogger) {
    for (Element element : classElement.getEnclosedElements()) {
      if (element.getKind() == ElementKind.METHOD) {
        ExecutableElement method = (ExecutableElement) element;
        String methodName = method.getSimpleName().toString();
        // check if it is a valid getter
        if ((methodName.equals(String.format("get%s", capitalizeFirstLetter(fieldName)))
            || methodName.equals(String.format("is%s", capitalizeFirstLetter(fieldName)))
            || (methodName.equals(fieldName) && startsWithIs(fieldName)))
            && !method.getModifiers().contains(PRIVATE)
            && !method.getModifiers().contains(STATIC)
            && method.getParameters().isEmpty()
            && TypeName.get(method.getReturnType()).equals(getTypeName())) {
          getterMethodName = methodName;
        }
        // check if it is a valid setter
        if ((methodName.equals(String.format("set%s", capitalizeFirstLetter(fieldName)))
            || (startsWithIs(fieldName) && methodName.equals(String.format("set%s",
            fieldName.substring(2, fieldName.length())))))
            && !method.getModifiers().contains(PRIVATE)
            && !method.getModifiers().contains(STATIC)
            && method.getParameters().size() == 1
            && TypeName.get(method.getParameters().get(0).asType()).equals(getTypeName())) {
          setterMethodName = methodName;
        }
      }
    }
    if (getterMethodName == null || setterMethodName == null) {
      // We disable the "private" field setting so that we can still generate
      // some code that compiles in an ok manner (ie via direct field access)
      isPrivate = false;

      errorLogger
          .logError("%s annotations must not be on private fields"
                  + " without proper getter and setter methods. (class: %s, field: %s)",
              EpoxyAttribute.class.getSimpleName(),
              classElement.getSimpleName(),
              fieldName);
    }
  }

  /**
   * Keeps track of annotations on the attribute so that they can be used in the generated setter
   * and getter method. Setter and getter annotations are stored separately since the annotation may
   * not target both method and parameter types.
   */
  private void buildAnnotationLists(List<? extends AnnotationMirror> annotationMirrors) {
    for (AnnotationMirror annotationMirror : annotationMirrors) {
      if (!annotationMirror.getElementValues().isEmpty()) {
        // Not supporting annotations with values for now
        continue;
      }

      ClassName annotationClass =
          ClassName.bestGuess(annotationMirror.getAnnotationType().toString());
      if (annotationClass.equals(ClassName.get(EpoxyAttribute.class))) {
        // Don't include our own annotation
        continue;
      }

      DeclaredType annotationType = annotationMirror.getAnnotationType();
      // A target may exist on an annotation type to specify where the annotation can
      // be used, for example fields, methods, or parameters.
      Target targetAnnotation = annotationType.asElement().getAnnotation(Target.class);

      // Allow all target types if no target was specified on the annotation
      List<ElementType> elementTypes =
          Arrays.asList(targetAnnotation == null ? ElementType.values() : targetAnnotation.value());

      AnnotationSpec annotationSpec = AnnotationSpec.builder(annotationClass).build();
      if (elementTypes.contains(ElementType.PARAMETER)) {
        setterAnnotations.add(annotationSpec);
      }

      if (elementTypes.contains(ElementType.METHOD)) {
        getterAnnotations.add(annotationSpec);
      }
    }
  }
}
