package zemberek.ner;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import zemberek.core.ScoredItem;
import zemberek.core.collections.IntValueMap;
import zemberek.core.data.Weights;
import zemberek.core.logging.Log;
import zemberek.morphology.TurkishMorphology;
import zemberek.ner.PerceptronNer.ClassModel;
import zemberek.ner.PerceptronNer.FeatureData;

public class PerceptronNerTrainer {

  private TurkishMorphology morphology;

  public PerceptronNerTrainer(TurkishMorphology morphology) {
    this.morphology = morphology;
  }

  public PerceptronNer train(
      NerDataSet trainingSet,
      NerDataSet devSet,
      int iterationCount,
      float learningRate) {

    Map<String, ClassModel> averages = new HashMap<>();
    Map<String, ClassModel> model = new HashMap<>();
    IntValueMap<String> counts = new IntValueMap<>();

    //initialize model weights for all classes.
    for (String typeId : trainingSet.typeIds) {
      model.put(typeId, new ClassModel(typeId));
      averages.put(typeId, new ClassModel(typeId));
    }

    for (int it = 0; it < iterationCount; it++) {

      int errorCount = 0;
      int tokenCount = 0;

      trainingSet.shuffle();

      for (NerSentence sentence : trainingSet.sentences) {

        for (int i = 0; i < sentence.tokens.size(); i++) {

          tokenCount++;
          NerToken currentToken = sentence.tokens.get(i);
          String currentId = currentToken.tokenId;

          FeatureData data = new FeatureData(morphology, sentence, i);
          List<String> sparseFeatures = data.getTextualFeatures();

          if (i > 0) {
            sparseFeatures.add("PreType=" + sentence.tokens.get(i - 1).tokenId);
          }
          if (i > 1) {
            sparseFeatures.add("2PreType=" + sentence.tokens.get(i - 2).tokenId);
          }
          if (i > 2) {
            sparseFeatures.add("3PreType=" + sentence.tokens.get(i - 3).tokenId);
          }

          ScoredItem<String> predicted = PerceptronNer
              .predictTypeAndPosition(model, sparseFeatures);
          String predictedId = predicted.item;

          if (predictedId.equals(currentId)) {
            // do nothing
            counts.addOrIncrement(predictedId);
            continue;
          }

          counts.addOrIncrement(currentId);
          counts.addOrIncrement(predictedId);
          errorCount++;

          model.get(currentId).updateSparse(sparseFeatures, +learningRate);
          model.get(predictedId).updateSparse(sparseFeatures, -learningRate);

          averages.get(currentId)
              .updateSparse(sparseFeatures, counts.get(currentId) * learningRate);
          averages.get(predictedId)
              .updateSparse(sparseFeatures, -counts.get(predictedId) * learningRate);
        }
      }
      Log.info("Iteration %d, Token error = %.6f", it + 1, (errorCount * 1d) / tokenCount);

      Map<String, ClassModel> copyModel = copyModel(model);
      averageWeights(averages, copyModel, counts);
      PerceptronNer ner = new PerceptronNer(copyModel, morphology);
      NerDataSet result = ner.test(devSet);
      testLog(devSet, result).dump();
    }

    averageWeights(averages, model, counts);

    Log.info("Training finished.");
    return new PerceptronNer(model, morphology);
  }

  private static Map<String, ClassModel> copyModel(Map<String, ClassModel> model) {
    Map<String, ClassModel> copy = new HashMap<>();
    for (String s : model.keySet()) {
      copy.put(s, model.get(s).copy());
    }
    return copy;
  }

  private static void averageWeights(
      Map<String, ClassModel> averages,
      Map<String, ClassModel> model,
      IntValueMap<String> counts) {
    for (String typeId : model.keySet()) {
      Weights w = model.get(typeId).sparseWeights;
      Weights a = averages.get(typeId).sparseWeights;
      for (String s : w) {
        w.put(s, w.get(s) - a.get(s) / counts.get(typeId));
      }
    }
  }


  public static class TestResult {

    int errorCount = 0;
    int tokenCount = 0;
    int truePositives = 0;
    int falsePositives = 0;
    int falseNegatives = 0;
    int testNamedEntityCount = 0;
    int correctNamedEntityCount = 0;

    double tokenErrorRatio() {
      return (errorCount * 1d) / tokenCount;
    }

    double tokenPresicion() {
      return (truePositives * 1d) / (truePositives + falsePositives);
    }

    //TODO: check this
    double tokenRecall() {
      return (truePositives * 1d) / (truePositives + falseNegatives);
    }

    double exactMatch() {
      return (correctNamedEntityCount * 1d) / testNamedEntityCount;
    }

    String dump() {
      List<String> lines = new ArrayList<>();
      lines.add(String.format("Token Error ratio   = %.6f", tokenErrorRatio()));
      Log.info(String.format("NE Token Precision  = %.6f", tokenPresicion()));
      Log.info(String.format("NE Token Recall     = %.6f", tokenRecall()));
      Log.info(String.format("Exact NER match     = %.6f", exactMatch()));
      return String.join(" ", lines);
    }

  }

  static void testReport(NerDataSet reference, NerDataSet prediction, Path reportPath)
      throws IOException {

    try (PrintWriter pw = new PrintWriter(reportPath.toFile(), "UTF-8")) {
      List<NerSentence> testSentences = reference.sentences;
      for (int i = 0; i < testSentences.size(); i++) {
        NerSentence ts = testSentences.get(i);
        pw.println(ts.content);
        NerSentence ps = prediction.sentences.get(i);
        for (int j = 0; j < ts.tokens.size(); j++) {
          NerToken tt = ts.tokens.get(j);
          NerToken pt = ps.tokens.get(j);
          if (tt.word.equals(tt.normalized)) {
            pw.println(String.format("%s %s -> %s", tt.word, tt.tokenId, pt.tokenId));
          } else {
            pw.println(
                String.format("%s:%s %s -> %s", tt.word, tt.normalized, tt.tokenId, pt.tokenId));
          }
        }
      }
      TestResult result = testLog(reference, prediction);
      pw.println(result.dump());
    }
  }

  static TestResult testLog(NerDataSet reference, NerDataSet prediction) {

    int errorCount = 0;
    int tokenCount = 0;
    int truePositives = 0;
    int falsePositives = 0;
    int falseNegatives = 0;
    int testNamedEntityCount = 0;
    int correctNamedEntityCount = 0;

    List<NerSentence> testSentences = reference.sentences;
    for (int i = 0; i < testSentences.size(); i++) {
      NerSentence ts = testSentences.get(i);
      NerSentence ps = prediction.sentences.get(i);
      for (int j = 0; j < ts.tokens.size(); j++) {
        NerToken tt = ts.tokens.get(j);
        NerToken pt = ps.tokens.get(j);
        if (!tt.tokenId.equals(pt.tokenId)) {
          errorCount++;
          if (tt.position == NePosition.OUTSIDE) {
            falsePositives++;
          }
          if (pt.position == NePosition.OUTSIDE) {
            falseNegatives++;
          }
        } else {
          if (tt.position != NePosition.OUTSIDE) {
            truePositives++;
          }
        }
        tokenCount++;
      }
      List<NamedEntity> namedEntities = ts.getNamedEntities();
      testNamedEntityCount += namedEntities.size();
      correctNamedEntityCount += ps.matchingNEs(namedEntities).size();
    }

    TestResult result = new TestResult();
    result.correctNamedEntityCount = correctNamedEntityCount;
    result.errorCount = errorCount;
    result.falseNegatives = falseNegatives;
    result.falsePositives = falsePositives;
    result.testNamedEntityCount = testNamedEntityCount;
    result.tokenCount = tokenCount;
    result.truePositives = truePositives;
    return result;
  }

}
