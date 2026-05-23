JC      = javac
JFLAGS  = -g -cp .
MAIN    = rpal20
SRCDIR  = src

SOURCES = $(MAIN).java \
          $(SRCDIR)/lexer/TokenType.java \
          $(SRCDIR)/lexer/Token.java \
          $(SRCDIR)/lexer/Lexer.java \
          $(SRCDIR)/parser/ASTNode.java \
          $(SRCDIR)/parser/Parser.java \
          $(SRCDIR)/standardizer/Standardizer.java \
          $(SRCDIR)/csemachine/Environment.java \
          $(SRCDIR)/csemachine/Delta.java \
          $(SRCDIR)/csemachine/Tuple.java \
          $(SRCDIR)/csemachine/BuiltInFunctions.java \
          $(SRCDIR)/csemachine/CSEMachine.java

.PHONY: all clean

all: $(SOURCES)
	$(JC) $(JFLAGS) $(SOURCES)

clean:
	rm -f *.class \
	      $(SRCDIR)/lexer/*.class \
	      $(SRCDIR)/parser/*.class \
	      $(SRCDIR)/standardizer/*.class \
	      $(SRCDIR)/csemachine/*.class
