// Copyright (C) 2026 LightningDSP contributors
// SPDX-License-Identifier: Apache-2.0
#pragma once
#if defined(__APPLE__) && defined(LDSP_AAUDIO_COMPAT_HOST_TEST)
#include "tests/elf64_platform.h"
#else
#include <elf.h>
#endif
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <sys/mman.h>

namespace ldsp::got {
constexpr size_t kTargets = 5;
constexpr size_t kMaxSegments = 32;
constexpr size_t kMaxSymbols = 262144;
struct Target { const char* name; uintptr_t original; uintptr_t replacement; bool optional = false; };
struct Image { uintptr_t base; const Elf64_Phdr* headers; size_t count; };
struct Slot { uintptr_t* address = nullptr; uintptr_t original = 0; uintptr_t replacement = 0; };
template<size_t Count> struct BasicPlan {
    static_assert(Count > 0 && Count <= kTargets, "bounded import transaction");
    std::array<Slot, Count> slots{};
    uintptr_t page = 0;
    size_t pageSize = 0;
    int expectedProtection = 0;
    bool alreadyApplied = false;
};
using Plan = BasicPlan<kTargets>;
struct Segment { uintptr_t begin=0,end=0; uint32_t flags=0; };
struct Layout {
    std::array<Segment,kMaxSegments> segments{};
    size_t count=0;
    uintptr_t relroBegin=0,relroEnd=0;
    bool contains(uintptr_t p, size_t size, uint32_t flags=PF_R) const {
        if (!size || p > UINTPTR_MAX-size) return false;
        for(size_t i=0;i<count;++i) if(p>=segments[i].begin && p+size<=segments[i].end &&
            (segments[i].flags&flags)==flags) return true;
        return false;
    }
    template<class T> bool read(uintptr_t p,T& value) const {
        if(!contains(p,sizeof(T))) return false;
        std::memcpy(&value,reinterpret_cast<const void*>(p),sizeof(T)); return true;
    }
    bool pointer(uintptr_t base,uint64_t raw,uintptr_t& result) const {
        const bool absolute=raw<=UINTPTR_MAX && contains(static_cast<uintptr_t>(raw),1);
        const bool relative=raw<=UINTPTR_MAX-base && contains(base+static_cast<uintptr_t>(raw),1);
        if(absolute && relative && raw!=base+raw) return false;
        if(!absolute && !relative) return false;
        result=absolute?static_cast<uintptr_t>(raw):base+static_cast<uintptr_t>(raw); return true;
    }
};
inline bool add(uintptr_t base,uint64_t offset,uintptr_t& out) {
    if(offset>UINTPTR_MAX-base) return false; out=base+static_cast<uintptr_t>(offset); return true;
}
inline bool symbolCount(const Layout& l,uintptr_t hash,uintptr_t gnu,size_t& count) {
    count=0;
    if(hash) {
        uint32_t buckets,chains;
        if(!l.read(hash,buckets)||!l.read(hash+4,chains)||!buckets||!chains||chains>kMaxSymbols||buckets>kMaxSymbols ||
            !l.contains(hash,(2ull+buckets+chains)*4)) return false;
        count=chains;
    }
    if(gnu) {
        uint32_t nb,offset,bloom,shift;
        if(!l.read(gnu,nb)||!l.read(gnu+4,offset)||!l.read(gnu+8,bloom)||!l.read(gnu+12,shift)||
            !nb||nb>kMaxSymbols||offset>kMaxSymbols||!bloom||bloom>kMaxSymbols||shift>=64) return false;
        uintptr_t buckets;
        if(!add(gnu,16ull+8ull*bloom,buckets)||!l.contains(buckets,4ull*nb)) return false;
        const uintptr_t chains=buckets+4ull*nb;
        size_t n=offset,visited=0;
        for(size_t i=0;i<nb;++i) {
            uint32_t index;
            if(!l.read(buckets+4*i,index)) return false;
            if(!index) continue;
            if(index<offset||index>=kMaxSymbols) return false;
            for(;;++index) {
                uint32_t chain;
                if(index>=kMaxSymbols||++visited>kMaxSymbols||!l.read(chains+4ull*(index-offset),chain)) return false;
                if(n<static_cast<size_t>(index)+1) n=static_cast<size_t>(index)+1;
                if(chain&1) break;
            }
        }
        if(count && count!=n) return false;
        count=n;
    }
    return count>0&&count<=kMaxSymbols;
}

// Parse loader-provided ELF64 metadata. All subsequent reads are bounded by PT_LOAD.
template<size_t Count>
inline bool prepare(const Image& image,const std::array<Target,Count>& targets,size_t pageSize,BasicPlan<Count>& out) {
    out={};
    if(!image.headers||!image.count||image.count>128||pageSize<4096||(pageSize&(pageSize-1))) return false;
    for(size_t i=0;i<Count;++i) {
        const auto& t=targets[i]; if(!t.name||!t.original||!t.replacement||t.original==t.replacement) return false;
        for(size_t j=0;j<i;++j) if(!std::strcmp(t.name,targets[j].name)||t.original==targets[j].original||t.replacement==targets[j].replacement) return false;
    }
    Layout l; uintptr_t dynamic=0; size_t dynamicSize=0; bool relro=false;
    for(size_t i=0;i<image.count;++i) {
        const auto& p=image.headers[i]; uintptr_t start,end;
        if(p.p_type!=PT_LOAD&&p.p_type!=PT_DYNAMIC&&p.p_type!=PT_GNU_RELRO) continue;
        if(!p.p_memsz||p.p_memsz>1073741824ull||!add(image.base,p.p_vaddr,start)||!add(start,p.p_memsz,end)) return false;
        if(p.p_type==PT_LOAD) {
            if(l.count==kMaxSegments||p.p_filesz>p.p_memsz||(p.p_flags&~(PF_R|PF_W|PF_X))) return false;
            for(size_t j=0;j<l.count;++j) if(start<l.segments[j].end&&end>l.segments[j].begin) return false;
            l.segments[l.count++]={start,end,p.p_flags};
        } else if(p.p_type==PT_DYNAMIC) {
            if(dynamic) return false; dynamic=start; dynamicSize=p.p_memsz;
        } else {
            if(relro||end>UINTPTR_MAX-(pageSize-1)) return false;
            relro=true; l.relroBegin=start&~(pageSize-1);l.relroEnd=(end+pageSize-1)&~(pageSize-1);
        }
    }
    Elf64_Ehdr eh;
    if(!l.read(image.base,eh)||std::memcmp(eh.e_ident,"\177ELF",4)||eh.e_ident[EI_CLASS]!=ELFCLASS64||
        eh.e_ident[EI_DATA]!=ELFDATA2LSB||eh.e_ident[EI_VERSION]!=EV_CURRENT||eh.e_type!=ET_DYN||eh.e_machine!=EM_AARCH64||
        eh.e_version!=EV_CURRENT||eh.e_ehsize!=sizeof(eh)||eh.e_phentsize!=sizeof(Elf64_Phdr)||eh.e_phnum!=image.count) return false;
    uintptr_t phdr;
    if(!add(image.base,eh.e_phoff,phdr)||phdr!=reinterpret_cast<uintptr_t>(image.headers)||
        !l.contains(phdr,image.count*sizeof(Elf64_Phdr))||!dynamic||dynamicSize%sizeof(Elf64_Dyn)||
        dynamicSize/sizeof(Elf64_Dyn)>4096||!l.contains(dynamic,dynamicSize)) return false;
    uint64_t sym=0,str=0,strSize=0,symEnt=0,jmp=0,pltSize=0,pltType=0,relaEnt=0,hash=0,gnu=0;
    bool ended=false;
    for(size_t i=0;i<dynamicSize/sizeof(Elf64_Dyn);++i) {
        Elf64_Dyn d; if(!l.read(dynamic+i*sizeof(d),d)) return false;
        if(d.d_tag==DT_NULL) {ended=true;break;}
        uint64_t* target=nullptr;
        switch(d.d_tag) {
            case DT_SYMTAB:target=&sym;break;case DT_STRTAB:target=&str;break;case DT_STRSZ:target=&strSize;break;
            case DT_SYMENT:target=&symEnt;break;case DT_JMPREL:target=&jmp;break;case DT_PLTRELSZ:target=&pltSize;break;
            case DT_PLTREL:target=&pltType;break;case DT_RELAENT:target=&relaEnt;break;case DT_HASH:target=&hash;break;
            case DT_GNU_HASH:target=&gnu;break;
            // Tagged globals can have protection bits not represented in maps' rwx fields.
            case 0x70000009: case 0x7000000b: case 0x7000000c:
            case 0x7000000d: case 0x7000000f: return false;
            default:break;
        }
        if(target) { if(*target) return false;*target=d.d_un.d_val; }
    }
    uintptr_t syms,strings,relocs,hashp=0,gnup=0;
    if(!ended||!sym||!str||!strSize||strSize>16777216||symEnt!=sizeof(Elf64_Sym)||!jmp||
        !pltSize||pltSize%sizeof(Elf64_Rela)||pltSize/sizeof(Elf64_Rela)>65536||pltType!=DT_RELA||
        relaEnt!=sizeof(Elf64_Rela)||!l.pointer(image.base,sym,syms)||!l.pointer(image.base,str,strings)||
        !l.pointer(image.base,jmp,relocs)||!l.contains(strings,strSize)||!l.contains(relocs,pltSize)||
        syms%alignof(Elf64_Sym)||relocs%alignof(Elf64_Rela)) return false;
    if(hash&&!l.pointer(image.base,hash,hashp)) return false;
    if(gnu&&!l.pointer(image.base,gnu,gnup)) return false;
    size_t symbolTotal;
    if(!symbolCount(l,hashp,gnup,symbolTotal)||!l.contains(syms,symbolTotal*sizeof(Elf64_Sym))) return false;
    std::array<bool,Count> seen{}; bool anyOriginal=false,anyReplacement=false;
    for(size_t i=0;i<pltSize/sizeof(Elf64_Rela);++i) {
        Elf64_Rela r; if(!l.read(relocs+i*sizeof(r),r)||ELF64_R_TYPE(r.r_info)!=R_AARCH64_JUMP_SLOT||r.r_addend) return false;
        const auto index=ELF64_R_SYM(r.r_info); Elf64_Sym s;
        if(index>=symbolTotal||!l.read(syms+index*sizeof(s),s)||s.st_name>=strSize) return false;
        const char* name=reinterpret_cast<const char*>(strings+s.st_name);
        if(!std::memchr(name,0,strSize-s.st_name)) return false;
        for(size_t t=0;t<Count;++t) if(!std::strcmp(name,targets[t].name)) {
            if(seen[t]||s.st_shndx!=SHN_UNDEF||s.st_value||ELF64_ST_TYPE(s.st_info)!=STT_FUNC||
                ELF64_ST_BIND(s.st_info)!=STB_GLOBAL||(s.st_other & 3)!=STV_DEFAULT) return false;
            uintptr_t slot;
            if(!add(image.base,r.r_offset,slot)||slot%alignof(uintptr_t)||!l.contains(slot,sizeof(uintptr_t),PF_R|PF_W)) return false;
            int prot=0; size_t owner=l.count;
            for(size_t j=0;j<l.count;++j) if(slot>=l.segments[j].begin&&slot+sizeof(uintptr_t)<=l.segments[j].end) {
                if(l.segments[j].flags&PF_X) return false;
                prot=PROT_READ|PROT_WRITE; owner=j;
            }
            const auto page=slot&~(pageSize-1);
            if(owner==l.count||page>UINTPTR_MAX-pageSize) return false;
            // The loader maps whole pages, but p_memsz can stop within the
            // final page. Bounds for all reads and the slot itself remain the
            // exact PT_LOAD bounds above. Refuse protection changes if another
            // segment shares this page, especially executable code.
            for(size_t j=0;j<l.count;++j)
                if(j!=owner && l.segments[j].begin<page+pageSize && l.segments[j].end>page) return false;
            if(relro&&page>=l.relroBegin&&page<l.relroEnd) prot=PROT_READ;
            if(!out.page) {out.page=page;out.pageSize=pageSize;out.expectedProtection=prot;}
            if(out.page!=page||out.expectedProtection!=prot) return false;
            for(size_t j=0;j<Count;++j) if(seen[j]&&out.slots[j].address==reinterpret_cast<uintptr_t*>(slot)) return false;
            const auto value=__atomic_load_n(reinterpret_cast<uintptr_t*>(slot),__ATOMIC_ACQUIRE);
            if(value==targets[t].original) anyOriginal=true;
            else if(value==targets[t].replacement) anyReplacement=true;
            else return false; // Existing hooks/lazy unresolved slots are not ours to replace.
            seen[t]=true;out.slots[t]={reinterpret_cast<uintptr_t*>(slot),targets[t].original,targets[t].replacement};
        }
    }
    for(size_t i=0;i<Count;++i) if(!seen[i]&&!targets[i].optional) return false;
    if(anyOriginal&&anyReplacement) return false;
    out.alreadyApplied=anyReplacement;return true;
}

enum class Result { Applied, AlreadyApplied, Rejected, RolledBack, Unsafe };
using Protect = bool (*)(void*,uintptr_t,size_t,int);
template<size_t Count>
inline Result apply(const BasicPlan<Count>& plan,int actualProtection,Protect protect,void* context) {
    if(!protect||!plan.page||plan.pageSize < 4096 || (plan.pageSize & (plan.pageSize - 1)) ||
        plan.page % plan.pageSize || plan.page > UINTPTR_MAX - plan.pageSize || actualProtection!=plan.expectedProtection||
        (actualProtection!=PROT_READ&&actualProtection!=(PROT_READ|PROT_WRITE))) return Result::Rejected;
    const bool writable=(actualProtection&PROT_WRITE)!=0;
    for(const auto& s:plan.slots) {
        const auto expected=plan.alreadyApplied?s.replacement:s.original;
        const auto address = reinterpret_cast<uintptr_t>(s.address);
        if(!s.address || address % alignof(uintptr_t) || address < plan.page ||
            address - plan.page > plan.pageSize - sizeof(uintptr_t) || !s.original || !s.replacement ||
            s.original == s.replacement || __atomic_load_n(s.address,__ATOMIC_ACQUIRE)!=expected) return Result::Rejected;
    }
    if(plan.alreadyApplied) return Result::AlreadyApplied;
    if(!writable&&!protect(context,plan.page,plan.pageSize,PROT_READ|PROT_WRITE)) return Result::Rejected;
    size_t changed=0;
    for(;changed<Count;++changed) {
        auto& s=plan.slots[changed]; uintptr_t expected=s.original;
        if(!__atomic_compare_exchange_n(s.address,&expected,s.replacement,false,__ATOMIC_ACQ_REL,__ATOMIC_ACQUIRE)) break;
    }
    auto rollback=[&]() {
        bool ok=true;
        while(changed) {const auto& s=plan.slots[--changed];uintptr_t expected=s.replacement;
            if(!__atomic_compare_exchange_n(s.address,&expected,s.original,false,__ATOMIC_ACQ_REL,__ATOMIC_ACQUIRE)) ok=false;}
        return ok;
    };
    if(changed!=Count) {
        const bool rolled=rollback();const bool restored=writable||protect(context,plan.page,plan.pageSize,actualProtection);
        return rolled&&restored?Result::RolledBack:Result::Unsafe;
    }
    if(writable||protect(context,plan.page,plan.pageSize,actualProtection)) return Result::Applied;
    // A failed final mprotect must not be announced as success. Reacquire write
    // permission, restore our pointers, then try the original protection again.
    if(!protect(context,plan.page,plan.pageSize,PROT_READ|PROT_WRITE)) return Result::Unsafe;
    const bool rolled=rollback();const bool restored=protect(context,plan.page,plan.pageSize,actualProtection);
    return rolled&&restored?Result::RolledBack:Result::Unsafe;
}
} // namespace ldsp::got
