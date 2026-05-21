grammar MyLang;

prog
    : topLevel* EOF
    ;

topLevel
    : funcDecl
    | structDecl
    | classDecl
    | stat
    ;

funcDecl
    : 'fun' returnTypeName ID '(' paramList? ')' block   #funcDeclRule
    ;

returnTypeName
    : typeName   #retTypeNamed
    | 'void'     #retTypeVoid
    ;

paramList
    : param (',' param)*
    ;

param
    : typeName ID
    ;

structDecl
    : 'struct' ID '{' fieldDecl* '}'   #structDeclRule
    ;

classDecl
    : 'class' ID '{' classMember* '}'   #classDeclRule
    ;

classMember
    : fieldDecl      #classMemberField
    | methodDecl     #classMemberMethod
    ;

fieldDecl
    : typeName ID
    ;

methodDecl
    : 'fun' returnTypeName ID '(' paramList? ')' block
    ;

block
    : '{' stat* '}'
    ;

stat
    : typeName '[' INT ']' '[' INT ']' ID              #matrixDecl
    | typeName '[' INT DOTDOT INT ']' ID               #rangedArrayDecl
    | typeName '[' INT ']' ID                          #arrayDecl
    | typeName ID ('=' expr)?                          #varDecl
    | ID '[' expr ']' '[' expr ']' '=' expr            #matrixAssign
    | ID '[' expr ']' '=' expr                         #arrayAssign
    | ID '.' ID '=' expr                               #fieldAssign
    | ID '=' expr                                      #assign
    | 'read' ID '[' expr ']' '[' expr ']'              #readMatrixStat
    | 'read' ID '[' expr ']'                           #readArrayStat
    | 'read' ID                                        #readStat
    | 'print' ID                                       #printIdStat
    | 'print' expr                                     #printStat
    | 'if' '(' expr ')' block ('else' 'if' '(' expr ')' block)* ('else' block)?   #ifStat
    | 'while' '(' expr ')' block           #whileStat
    | 'for' ID '=' expr 'to' expr block    #forStat
    | 'return' expr?                       #returnStat
    | ID '(' argList? ')'                  #funcCallStat
    | ID '.' ID '(' argList? ')'           #methodCallStat
    ;

argList
    : expr (',' expr)*
    ;

typeName
    : 'int'     #typeInt
    | 'real'    #typeReal
    | 'float32' #typeFloat32
    | 'float64' #typeFloat64
    | 'bool'    #typeBool
    | 'string'  #typeString
    | ID        #typeStruct
    ;

expr: orExpr;

orExpr
    : orExpr 'OR' xorExpr  #orOp
    | xorExpr               #orPass
    ;

xorExpr
    : xorExpr 'XOR' andExpr  #xorOp
    | andExpr                  #xorPass
    ;

andExpr
    : andExpr 'AND' notExpr  #andOp
    | notExpr                  #andPass
    ;

notExpr
    : 'NOT' notExpr  #notOp
    | cmpExpr         #notPass
    ;

cmpExpr
    : addExpr EQ  addExpr  #eqOp
    | addExpr NEQ addExpr  #neOp
    | addExpr LE  addExpr  #leOp
    | addExpr GE  addExpr  #geOp
    | addExpr LT  addExpr  #ltOp
    | addExpr GT  addExpr  #gtOp
    | addExpr               #cmpPass
    ;

addExpr
    : addExpr '+' mulExpr  #addOp
    | addExpr '-' mulExpr  #subOp
    | mulExpr               #addPass
    ;

mulExpr
    : mulExpr '*' castExpr  #mulOp
    | mulExpr '/' castExpr  #divOp
    | castExpr               #mulPass
    ;

castExpr
    : '(' typeName ')' castExpr  #castOp
    | unaryExpr                   #castPass
    ;

unaryExpr
    : '-' primaryExpr  #negOp
    | primaryExpr       #unaryPass
    ;

primaryExpr
    : INT                               #intLit
    | REAL                              #realLit
    | 'true'                            #trueLit
    | 'false'                           #falseLit
    | STRING_LIT                        #stringLit
    | ID '[' expr ']' '[' expr ']'      #matrixRef
    | ID '[' expr ']'                   #arrayRef
    | ID '(' argList? ')'               #funcCallExpr
    | ID '.' ID '(' argList? ')'        #methodCallExpr
    | ID '.' ID                         #fieldRef
    | ID                                #idRef
    | '(' expr ')'                      #parenExpr
    ;

TO:     'to';
FUN:    'fun';
STRUCT: 'struct';
CLASS:  'class';
VOID:   'void';
RETURN: 'return';
IF:     'if';
ELSE:   'else';
WHILE:  'while';
FOR:    'for';

EQ: '==';
NEQ: '!=';
LE: '<=';
GE: '>=';
LT: '<';
GT: '>';

DOTDOT: '..';

REAL
    : [0-9]+ '.' [0-9]+
    ;

INT
    : [0-9]+
    ;

STRING_LIT
    : '"' (~["\\\r\n])* '"'
    ;

ID
    : [a-zA-Z_][a-zA-Z0-9_]*
    ;

NEWLINE
    : '\r'? '\n' -> skip
    ;

WS
    : [ \t]+ -> skip
    ;

LINE_COMMENT
    : '//' ~[\r\n]* -> skip
    ;
