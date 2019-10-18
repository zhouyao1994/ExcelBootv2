import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
//import org.junit.Assert;

import javax.lang.model.element.Modifier;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

//import com.sun.tools.javac.util.List;
//import java.util.*;


public class CodeGenerate {
  public static void main(String[] args) throws IOException {
    String path =args[0];
//    genarateDemo();

    //1.从excel读取配置文件
//    List<TestCaseBean> testCaseBeans = GeneralBeans();
    List<TestCaseBean> testCaseBeans = readXlsxFile(path);

    //2.处理
    //一个包下可以有多个类，每个类有多个方法。每个方法是一个testcase。
    //按 包名分组   （同一个包下可以有多个类）
    Map<String, List<TestCaseBean>> collect = testCaseBeans
            .stream()
            .collect(Collectors.groupingBy(TestCaseBean::getPackageName));

    //3.输出
    //按分组结果 在按 类名分组一次  （同一个类下可以有多个测试方法）
    collect.forEach((pakegeName, v) -> {
      Map<String, List<TestCaseBean>> classGroupBy = v
              .stream()
              .collect(Collectors.groupingBy(TestCaseBean::getClassName));

      classGroupBy.forEach((className, cases) -> {
        try {
          System.out.println("=============");
          GenerateFiles(pakegeName, className, cases,testCaseBeans).writeTo(System.out);
//          GenerateFiles(pakegeName, className, cases,testCaseBeans).writeTo(new File("test"));
        } catch (IOException e) {
          e.printStackTrace();
        }
      });

    });
  }

  /**
   * 输出java类文件，µ≥
   * @param pakegeName
   * @param className
   * @param cases
   * @param testCaseBeans
   * @return
   */
  private static JavaFile GenerateFiles(String pakegeName,
                                        String className,
                                        List<TestCaseBean> cases,
                                        List<TestCaseBean> testCaseBeans) {
    TypeSpec.Builder builder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL);
    //1.先创建方法，有多个case

    for (TestCaseBean caseIt : cases) {
//    TestCaseBean caseIt = cases.get(0);

      String methodName = caseIt.getMethodName();
      String caseItClassName = caseIt.getClassName();
      String mainBO = caseIt.getMainBO();
      String testMethod = caseIt.getTestMethod();
      Map<String, String> fields = caseIt.getFields();
      String assertSentence = caseIt.getAssertSentence();
      String nOrP = caseIt.getNorP();
      String forwardDependencyNum = caseIt.getForwardDependencyNum();

      //1.1 创建单个方法
      MethodSpec.Builder builderLocal = MethodSpec.methodBuilder(methodName)
              .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
              .returns(void.class)
              .addParameter(String[].class, "args")
              .addStatement("// 测试用例")
              .addStatement("// "+caseIt.getComment());


      //1.1.1添加前置依赖项
      List<TestCaseBean> forwardCase = testCaseBeans.stream().filter(it ->
              it.getTestCaseNum().equals(forwardDependencyNum)
      ).collect(Collectors.toList());

      forwardCase.forEach(it->
              builderLocal.addStatement("$N()",it.getMethodName()));



      //1.2 为新建BO设置属性
      //new 对象,N表示name，S表示String
      //新建大BO
      builderLocal.addStatement("$N $N = new $N()", mainBO,mainBO.toLowerCase(), mainBO);


      Set<String> fieldStrings = fields.keySet();
      List<String> keys = new ArrayList<>(fieldStrings);
      // 1.2.1获得不同的BO名字
      List<String> bos = keys.stream().map(it -> it.split("\\.")[0]).distinct().collect(Collectors.toList());

      //1.2.2新建小BO,并放入到大Bo中
      bos.forEach(it->builderLocal.addStatement("$N $N = new $N()",it,it.toLowerCase(),it));
      bos.forEach(it->builderLocal.addStatement("$N.set$N = $N",mainBO.toLowerCase(),it.toLowerCase(),it.toLowerCase()));


      //1.2.3对Bo赋值
      fields.forEach((key, value) -> {
        if (key.contains(".")) {
          String varName = key.split("\\.")[0].toLowerCase();
          String fild = key.split("\\.")[1];
          builderLocal.addStatement("$N.set$N($S)", varName,fild, value);
        }
      });

      //1.3 run测试函数的逻辑代码
//      builderLocal.addStatement("$N($N)", testMethod, "testObject");

      switch (nOrP) {
        case ("P"):
          //1.4添加 正常测试用例的：Assert 参数
          builderLocal.addStatement("$N($N)", testMethod, mainBO.toLowerCase());
          Arrays.asList(assertSentence.split("\\;")).forEach(it->
                  builderLocal.addStatement(it)
          );
          break;
        case ("N"):
          //1.5 添加异常测试用例的 Assert 参数
          builderLocal.beginControlFlow("try")
                  .addStatement("$N($N)", testMethod, mainBO.toLowerCase())
                  .addStatement("Assert.fail()")
                  .nextControlFlow("catch ($T e)", Exception.class);
          Arrays.asList(assertSentence.split("\\;")).forEach(it->
                  builderLocal.addStatement(it)
          );

          builderLocal.endControlFlow();
          break;
        default:
          break;
      }


      MethodSpec localClass = builderLocal
              .build();
      //2.把方法放到类中
      builder.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
              .addMethod(localClass);
    }

    //3.把类放到包中，返回文件
    return JavaFile.builder(pakegeName, builder.build())
            .build();
  }

  private static List<TestCaseBean> readXlsxFile(String filePath) throws IOException {
//    String path = "/Users/zhouyao/Downloads/CodeDemov1.xlsx";
//    String path = "/Users/zhouyao/Downloads/周耀-代码生成模板-v1.1.xlsx";
    String path=filePath;
    XSSFWorkbook workbook = readFile(path);
    XSSFSheet sheet1 = workbook.getSheet("Sheet1");
    int rowNum = sheet1.getLastRowNum();

    int testCaseNum = 0;
    int commentNum=1;
    int pakegeColNum = 2;
    int classColNum = 3;
    int methodColNum = 4;
    int norPColNum = 5;
    int mainboColNum = 6;
    int assertSentenceNum = 7;
    int testMethdNum = 8;
    int forwardDependencyNum = 9;
    int fildStartNum = 10;

    List<TestCaseBean> cases = new ArrayList<TestCaseBean>();
    //第0行为表头
    for (int i = 1; i < rowNum + 1; i++) {
      ArrayList<ArrayList<String>> filds = new ArrayList<>();
      HashMap<String, String> fildHashmap = new HashMap<>();

      for (int j = fildStartNum; j < sheet1.getRow(i).getLastCellNum(); j++) {
        fildHashmap.put(
                parseSheetContent(sheet1, 0, j),//表头
                parseSheetContent(sheet1, i, j));//值
      }

      TestCaseBean testCaseBeans = new TestCaseBean(
               parseSheetContent(sheet1, i, testCaseNum),
               parseSheetContent(sheet1, i, commentNum),
               parseSheetContent(sheet1, i, pakegeColNum),
              parseSheetContent(sheet1, i, classColNum),
              parseSheetContent(sheet1, i, methodColNum),
              parseSheetContent(sheet1, i, mainboColNum),
              parseSheetContent(sheet1, i, testMethdNum),
              fildHashmap,
//              Integer.valueOf(parseSheetContent(sheet1, i, mainboColNum))
              parseSheetContent(sheet1, i, forwardDependencyNum),
              parseSheetContent(sheet1, i, assertSentenceNum),
              parseSheetContent(sheet1, i, norPColNum)
      );

      cases.add(testCaseBeans);
    }
    return cases;
  }

  private static String parseSheetContent(XSSFSheet sheet, int row, int cell) {
    String s="";
    try {
      s = sheet.getRow(row).getCell(cell).toString();
    } catch (Exception e) {
    }finally {
      return s;
    }
  }

  private static XSSFWorkbook readFile(String filename) throws IOException {
    try (FileInputStream fis = new FileInputStream(filename)) {
      return new XSSFWorkbook(fis);        // NOSONAR - should not be closed here
    }
  }


  private static void genarateDemo() throws IOException {
    HashMap<String, String> stringStringHashMap = new HashMap<>();
    stringStringHashMap.put("methodName", "Invivid");
    stringStringHashMap.put("RunMethod", "mainLIneA");
    stringStringHashMap.put("classBo", "BO");
    stringStringHashMap.put("FildA", "nameA");
    stringStringHashMap.put("FildB", "nameB");
    stringStringHashMap.put("PackageName", "org.xx.yy.zz");
//    System.out.println(stringStringHashMap);
//    System.out.println(stringStringHashMap);

    //按包名分组生成

    //按类名分组生成


    String methodName = stringStringHashMap.remove("methodName");
    String classBo = stringStringHashMap.remove("classBo");
    String Runmethod = stringStringHashMap.remove("RunMethod");
    String packageName = stringStringHashMap.remove("PackageName");

    MethodSpec.Builder builder = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(void.class)
            .addParameter(String[].class, "args")
            .addStatement("$T.out.println($S)", System.class, "Hello, JavaPoet!")
            //1.new 对象,N表示name，S表示String
            .addStatement("$N testObject = new $N()", classBo, classBo);
    //2.设置值
    stringStringHashMap.forEach((key, value) -> {
      builder.addStatement("testObject.set$N($S)", key, value);
    });

    //3.run正常的逻辑
    builder.addStatement("$N($N)", Runmethod, "testObject");

    MethodSpec main = builder
            .build();

    TypeSpec helloWorld = TypeSpec.classBuilder("HelloWorld")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(main)
            .build();

    JavaFile javaFile = JavaFile.builder(packageName, helloWorld)
            .build();


    javaFile.writeTo(System.out);
  }
}
