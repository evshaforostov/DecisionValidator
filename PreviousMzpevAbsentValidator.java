package msk.validation;

import lombok.Setter;
import msk.domain.*;
import msk.service.ApplicationService;
import msk.service.DecisionService;
import msk.service.DocumentService;
import msk.service.PortfolioService;
import msk.service.decision.calculator.MzpevCalculator;
import msk.utils.*;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static msk.utils.ValidationTypeClassifier.ANOTHER_MZPEV;

@Component
@Setter
public class PreviousMzpevAbsentValidator implements DecisionValidator {
    @Resource
    private DecisionService decisionService;
    @Resource
    private ApplicationService applicationService;
    @Resource
    private DocumentService documentService;
    @Resource
    PortfolioService portfolioService;
    @Resource
    MzpevCalculator mzpevCalculator;


    @Override
    public Validation validate(Long docId) {
        ValidationResultClassifier res = ValidationResultClassifier.ERROR;
        String errorDesc = null;
        Decision decision = decisionService.getDecisionById(docId);
        Doc appl = documentService.getDocById(decision.getApplication().getId());
        Date anyDate = DataTypeUtil.toDate(DataTypeUtil.toLocalDate(appl.getAccDate()).plusYears(5));
        List<Application> mzpevs = applicationService.getApplicationsByOph(decision.getPortfolio().getOperHistory().getId(),
                anyDate, DocumentTypeClassifier.MZRK, ExpenseDirectionClassifier.MONTHLY_PAYMENT_HELP);
        List<Application> positiveApprovedMzpevs = mzpevs.stream()
                .peek(app -> {
                    Decision dec = applicationService.getStatedDecisionByAppId(app.getId());
                    app.setDecision(dec);
                })
                .filter(app -> app.getDecision() != null &&
                        DecisionStatusClassifier.APPROVE.is(app.getDecision().getStatus().getId()) &&
                        app.getDecision().getIsApprove())
                .collect(Collectors.toList());
        List<Child> applChildren = portfolioService.getChildrenList(appl.getId(), null);

        if (positiveApprovedMzpevs.size() > 0) {
            Application appWithDoubledChild = positiveApprovedMzpevs.stream()
                    .filter(app -> isChildDoubled(applChildren, portfolioService.getChildrenListByAppId(app.getId())))
                    .findFirst()
                    .orElse(null);

            if (appWithDoubledChild != null) {
                errorDesc = String.format("В ПС МСК найдено заявление МЗРК %s, %s, %s с положительным решением с такими же детьми",
                        DataTypeUtil.formatDate(appWithDoubledChild.getAccDate() != null ? appWithDoubledChild.getAccDate()
                                : appWithDoubledChild.getDate()),
                        appWithDoubledChild.getIncomingNum(),
                        appWithDoubledChild.getOrgUnit().getCode());
                res = ValidationResultClassifier.ERROR;
                return new Validation(getValidationTypeId(), docId, res, errorDesc);
            }
        }

        if (applChildren.size() > 0 && hasChildrenNotConsInCalcInPosApprovMzpev(applChildren, positiveApprovedMzpevs)) {
            return new Validation(getValidationTypeId(), docId, ValidationResultClassifier.SUCCESS, errorDesc);
        }

        // Ищем до даты заявления
        Date maxDate = DataTypeUtil.toDate(DataTypeUtil.toLocalDate(appl.getAccDate()).minusDays(1));
        List<Application> mzpevsBefore = applicationService.getApplicationsByOph(decision.getPortfolio().getOperHistory().getId(),
                maxDate, DocumentTypeClassifier.MZRK, ExpenseDirectionClassifier.MONTHLY_PAYMENT_HELP);
        if (mzpevsBefore.size() == 0) {
            return new Validation(getValidationTypeId(), docId, ValidationResultClassifier.SUCCESS, errorDesc);
        }
        Application mzpevWithoutDec = mzpevsBefore.stream()
                .filter(ap -> !appl.getId().equals(ap.getId()) && applicationService.getStatedDecisionByAppId(ap.getId()) == null)
                .findFirst()
                .orElse(null);
        if (mzpevWithoutDec == null) {
            for (Application mzpev : mzpevsBefore) {
                Decision dec = applicationService.getStatedDecisionByAppId(mzpev.getId());
                if (DecisionStatusClassifier.REFUSE.is(dec.getStatus().getId()) && dec.getIsApprove()) {//отрицательное утвержденное решение
                    res = ValidationResultClassifier.SUCCESS;
                } else if (DecisionStatusClassifier.REFUSE.is(dec.getStatus().getId())) {//отрицательное неутвержденное решение
                    res = ValidationResultClassifier.SUCCESS;
                } else {//любое положительное решение
                    List<Child> existedAppChildren = portfolioService.getChildrenList(mzpev.getId(), null);
                    boolean isDouble = isChildDoubled(applChildren, existedAppChildren);
                    if (isDouble) {
                        errorDesc = String.format("В ПС МСК найдено заявление МЗРК %s, %s, %s с положительным решением с такими же детьми",
                                DataTypeUtil.formatDate(mzpev.getAccDate() != null ? mzpev.getAccDate()
                                        : mzpev.getDate()),
                                mzpev.getIncomingNum(),
                                mzpev.getOrgUnit().getCode());
                        res = ValidationResultClassifier.ERROR;
                        break;
                    }
                }
            }
        } else {
            errorDesc = String.format("В ПС МСК найдено заявление %s %s %s без решения",
                    DataTypeUtil.formatDate(mzpevWithoutDec.getAccDate() != null ? mzpevWithoutDec.getAccDate()
                            : mzpevWithoutDec.getDate()),
                    mzpevWithoutDec.getIncomingNum(),
                    mzpevWithoutDec.getOrgUnit().getFormattedCodeName());
        }
        return new Validation(getValidationTypeId(), docId, res, errorDesc);
    }

    @Override
    public Integer getValidationTypeId() {
        return ANOTHER_MZPEV.getId();
    }

    private boolean isChildDoubled(List<Child> newAppChildren, List<Child> existedAppChildren) {
        return newAppChildren.stream()
                .filter(Child::getIsConsInCalc)
                .filter(child -> child.getNotInCalcReason() == null)
                .map(child -> mzpevCalculator.isChildDoubled(child, existedAppChildren))
                .anyMatch(isDoubled -> isDoubled);
    }

    private boolean hasChildrenNotConsInCalcInPosApprovMzpev(List<Child> applChildren, List<Application> positiveApprovedMzpevs) {
        for (Application mzpev : positiveApprovedMzpevs) {
            Decision mzpevDecision = applicationService.getStatedDecisionByAppId(mzpev.getId());
            List<Child> mzpevChildren = new ArrayList<>();
            if (mzpevDecision != null) {
                mzpevChildren = portfolioService.getChildrenList(mzpev.getId(), null);
            }
            List<Child> finalMzpevChildren = mzpevChildren;
            List<Child> applChildrenNotInMvzpevChildren = applChildren.stream()
                    .filter(e -> finalMzpevChildren.stream().noneMatch(mc ->
                            DataTypeUtil.normalize(mc.getLastname()).equals(DataTypeUtil.normalize(e.getLastname())) &&
                                    DataTypeUtil.normalize(mc.getName()).equals(DataTypeUtil.normalize(e.getName())) &&
                                    DataTypeUtil.normalize(mc.getPatronymic()).equals(DataTypeUtil.normalize(e.getPatronymic())) &&
                                    mc.getBirthDate().equals(e.getBirthDate()) &&
                                    DataTypeUtil.normalize(DataTypeUtil.getSnilsStr(mc.getSnils())).equals( DataTypeUtil.normalize(DataTypeUtil.getSnilsStr(e.getSnils())))
                            )
                    )
                    .collect(Collectors.toList());
            if (applChildrenNotInMvzpevChildren.size() > 0) {
                return true;
            }
            List<Child> mzpevChildrenInAppl = mzpevChildren.stream()
                    .filter(e -> applChildren.stream().anyMatch(ac ->
                            DataTypeUtil.normalize(ac.getLastname()).equals(DataTypeUtil.normalize(e.getLastname())) &&
                            DataTypeUtil.normalize(ac.getName()).equals(DataTypeUtil.normalize(e.getName())) &&
                            DataTypeUtil.normalize(ac.getPatronymic()).equals(DataTypeUtil.normalize(e.getPatronymic())) &&
                            ac.getBirthDate().equals(e.getBirthDate()) &&
                            DataTypeUtil.normalize(DataTypeUtil.getSnilsStr(ac.getSnils())).equals( DataTypeUtil.normalize(DataTypeUtil.getSnilsStr(e.getSnils())))
                            )
                    )
                    .collect(Collectors.toList());
            List<Child> notIsConsInCalc = mzpevChildrenInAppl.stream()
                    .filter(c -> c.getIsConsInCalc() == null || !c.getIsConsInCalc() || c.getNotInCalcReason() != null)
                    .collect(Collectors.toList());

            if (notIsConsInCalc.size() > 0) {
                return true;
            }
        }
        return false;
    }
}
