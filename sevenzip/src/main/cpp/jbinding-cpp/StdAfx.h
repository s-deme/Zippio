#pragma once
#include "../7zip/CPP/Common/Common.h"
#include "../7zip/CPP/Common/MyCom.h"
// JNI adapters derive from each other; their IUnknown implementation must stay
// public and overridable. The official engine's final implementations are unchanged.
#define JB_QI_BEGIN(i) public: STDMETHOD(QueryInterface)(REFGUID iid, void **outObject) { \
  *outObject = NULL; if (iid == IID_IUnknown) { *outObject = (IUnknown *)(i *)this; }
#define JB_QI_ENTRY(i) else if (iid == IID_ ## i) { *outObject = (i *)this; }
#define JB_QI_END else return E_NOINTERFACE; AddRef(); return S_OK; }
#define JB_REFS public: STDMETHOD_(ULONG, AddRef)() { return ++_m_RefCount; } \
  STDMETHOD_(ULONG, Release)() { if (--_m_RefCount) return _m_RefCount; delete this; return 0; }
#define MY_UNKNOWN_IMP JB_QI_BEGIN(IUnknown) JB_QI_END JB_REFS
#define MY_UNKNOWN_IMP1(i) JB_QI_BEGIN(i) JB_QI_ENTRY(i) JB_QI_END JB_REFS
#define MY_UNKNOWN_IMP2(i,j) JB_QI_BEGIN(i) JB_QI_ENTRY(i) JB_QI_ENTRY(j) JB_QI_END JB_REFS
#define MY_UNKNOWN_IMP3(i,j,k) JB_QI_BEGIN(i) JB_QI_ENTRY(i) JB_QI_ENTRY(j) JB_QI_ENTRY(k) JB_QI_END JB_REFS
#define __m_RefCount _m_RefCount
typedef UInt64 UINT64;
