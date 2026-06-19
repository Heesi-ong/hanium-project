// 랜딩페이지 섹션들을 순서대로 조립하는 페이지 컴포넌트다.
import "./HomePage.css";
import {
  CoachSection,
  ConcernSection,
  FaqSection,
  FeaturesSection,
  FinalCtaSection,
  HeroSection,
  HomeFooter,
  HowItWorksSection,
  ResultPreviewSection,
  SecuritySection,
  UseCasesSection,
} from "../features/home/HomeSections";

function HomePage() {
  return (
    <main className="home-page">
      <HeroSection />
      <ConcernSection />
      <FeaturesSection />
      <ResultPreviewSection />
      <HowItWorksSection />
      <UseCasesSection />
      <CoachSection />
      <SecuritySection />
      <FaqSection />
      <FinalCtaSection />
      <HomeFooter />
    </main>
  );
}

export default HomePage;
