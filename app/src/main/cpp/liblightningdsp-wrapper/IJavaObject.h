//
// Created by tim on 01.07.22.
//

#ifndef ROOTLESSLIGHTNINGDSP_IJAVAOBJECT_H
#define ROOTLESSLIGHTNINGDSP_IJAVAOBJECT_H

#include <jni.h>

class IJavaObject {
public:
    IJavaObject(JNIEnv* env) : _env(env) {};
    virtual bool isValid() const = 0;
    virtual jobject getJavaReference() = 0;

protected:
    JNIEnv* _env;
};

#endif //ROOTLESSLIGHTNINGDSP_IJAVAOBJECT_H
