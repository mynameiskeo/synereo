package com.biosimilarity.seleKt.model.ill.vm.illvm.Absyn; // Java Package generated by the BNF Converter.

public class FST extends Instruction {
  public final String illfst_;

  public FST(String p1) { illfst_ = p1; }

  public <R,A> R accept(com.biosimilarity.seleKt.model.ill.vm.illvm.Absyn.Instruction.Visitor<R,A> v, A arg) { return v.visit(this, arg); }

  public boolean equals(Object o) {
    if (this == o) return true;
    if (o instanceof com.biosimilarity.seleKt.model.ill.vm.illvm.Absyn.FST) {
      com.biosimilarity.seleKt.model.ill.vm.illvm.Absyn.FST x = (com.biosimilarity.seleKt.model.ill.vm.illvm.Absyn.FST)o;
      return this.illfst_.equals(x.illfst_);
    }
    return false;
  }

  public int hashCode() {
    return this.illfst_.hashCode();
  }


}